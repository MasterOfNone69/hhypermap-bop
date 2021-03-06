/*
 * Copyright 2016 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.harvard.gis.hhypermap.bopws

import com.codahale.metrics.annotation.Timed
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.FacetField
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.common.SolrDocument
import org.apache.solr.common.SolrException
import org.apache.solr.common.params.FacetParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.params.SolrParams
import org.apache.solr.common.util.NamedList
import org.locationtech.spatial4j.distance.DistanceUtils
import org.locationtech.spatial4j.shape.Rectangle
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.StreamingOutput

//  Solr fields: (TODO make configurable?)
private val ID_FIELD = "id"
private val TIME_FILTER_FIELD = "created_at"
private val TIME_FILTER_DV_FIELD = "created_at_dv"
private val TIME_SORT_FIELD = "created_at_dv"
private val GEO_FILTER_FIELD = "coord" // and assume units="kilometers" for all spatial fields
private val GEO_HEATMAP_FIELD = "coord_hm"
private val GEO_POS_SENT_HEATMAP_FIELD = "coordSentimentPos_hm"
private val GEO_SORT_FIELD = "coord"
private val TEXT_FIELD = "text"
private val USER_FIELD = "user_name"
//private val FL_PARAM = "id,created_at,coord,user_name,text" from Solr instead

private fun String.parseGeoBox() = parseGeoBox(this)

private fun setRouteParams(solrQuery: SolrQuery, leftInst: Instant?, rightInst: Instant?) {
  // In Solr we've customized the date routing to hit the right shards.
  solrQuery.set("hcga.start", leftInst?.toString())
  solrQuery.set("hcga.end", rightInst?.toString())
}

@Api
@Path("/tweets") // TODO make path configurable == Collection
class SearchWebService(
        val solrClient: SolrClient, val overrideSolrParams: SolrParams = ModifiableSolrParams()) {

  // note: I'd prefer immutable; Jersey seems to handle it but Swagger is confused (NPE)
  data class ConstraintsParams(
    @set:QueryParam("q.text") @set:Size(min = 1)
    @set:ApiParam("Constrains docs by keyword search query.")
    var qText: String? = null,

    @set:QueryParam("q.user") @set:Size(min = 1)
    @set:ApiParam("Constrains docs by matching exactly a certain user")
    var qUser: String? = null,

    @set:QueryParam("q.time") @set:Pattern(regexp = """\[(\S+) TO (\S+)\]""")
    @set:ApiParam("Constrains docs by time range.  Either side can be '*' to signify" +
            " open-ended. Otherwise it must be in either format as given in the example." +
            " UTC time zone is implied.", // TODO add separate TZ param?
            example = "[2017-01-01 TO 2017-04-01T00:00:00]")
    var qTime: String? = null,

    @set:QueryParam("q.geo") @set:Pattern(regexp = """\[(\S+,\S+) TO (\S+,\S+)\]""")
    @set:ApiParam("A rectangular geospatial filter in decimal degrees going from the lower-left" +
            " to the upper-right.  The coordinates are in lat,lon format.",
            example = "[-90,-180 TO 90,180]")
    var qGeo: String? = null

    // TODO more, q.geoPath q.lang, ...
  ) {

    @get:JsonIgnore internal val qGeoRect: Rectangle? by lazy {
      qGeo?.parseGeoBox()
    }

    fun applyToSolrQuery(solrQuery: SolrQuery) {
      // Determine if we should tell solr to do range faceting using the DocValues method. Ideally
      // Solr would determine this automatically.  We tell it to do so when the per-shard result is
      // likely very much filtered (< 5%).
      var dvOpt = false

      // q.text:
      val qText = this.qText?.trim()
      if (qText != null && qText != "*" && qText != "*:*") {
        dvOpt = true
        solrQuery.query = qText
        // TODO will wind up in 'fq'?  If not we should use fq if no relevance sort
      }

      // q.user
      if (qUser != null) {
        dvOpt = true
        solrQuery.addFilterQuery("{!field f=$USER_FIELD tag=$USER_FIELD}$qUser")
      }

      // q.time
      if (qTime != null) {
        val (leftInst, rightInst) = parseDateTimeRange(qTime) // parses multiple formats
        if (leftInst != null || rightInst != null) {
          // TODO if time range is very small then set dvOpt
          val leftStr = leftInst?.toString() ?: "*" // normalize to Solr
          val rightStr = rightInst?.toString() ?: "*" // normalize to Solr
          //TODO: if rightStr is >= NOW, modify to be midnight to be more cacheable
          // note: tag to exclude in a.time
          solrQuery.addFilterQuery("{!field tag=$TIME_FILTER_FIELD f=$TIME_FILTER_FIELD}" +
                  "[$leftStr TO $rightStr]")
          setRouteParams(solrQuery, leftInst, rightInst)
        }

        // TODO add caching header if we didn't need to contact the realtime shard? expire at 1am
        //   or could we get Solr to do caching logic in distributed?
      }

      // q.geo
      val qGeoRect = this.qGeoRect
      if (qGeoRect != null && qGeoRect != SPATIAL4J_CTX.worldBounds) {
        // all our data has a spatial point, thus a world query is useless.
        dvOpt = dvOpt || qGeoRect.getArea(SPATIAL4J_CTX) < SPATIAL4J_CTX.worldBounds.getArea(SPATIAL4J_CTX) * 0.05
        // note: can't use {!field} since it's the Lucene QParser that parses ranges
        // note: tag to exclude in a.hm
        solrQuery.addFilterQuery("{!lucene tag=$GEO_FILTER_FIELD df=$GEO_FILTER_FIELD}$qGeo")
      }

      //TODO q.geoPath

      //TODO q.lang


      if (dvOpt) {
        solrQuery.set(FacetParams.FACET_RANGE_METHOD, "dv")
      }
    }
  }

  @Path("/search")
  @ApiOperation(value = "Search/analytics endpoint; highly configurable. Not for bulk doc retrieval.",
          notes = """The q.* parameters qre query/constraints that limit the matching documents.
 The d.* params control returning the documents. The a.* params are faceting/aggregations on a
 field of the documents.  The *.limit params limit how many top values/docs to return.  Some of
 the formatting and response structure has strong similarities with Apache Solr, unsurprisingly.""")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Timed
  fun search(

          @BeanParam
          qConstraints: ConstraintsParams,

          @QueryParam("d.docs.limit") @DefaultValue("0") @Min(0) @Max(100)
          @ApiParam("How many documents to return in the search results.")
          aDocsLimit: Int,

          @QueryParam("d.docs.sort") @DefaultValue("time")
          @ApiParam("How to order the documents before returning the top X." +
                  " 'score' is keyword search relevancy. 'time' is time descending." +
                  " 'distance' is the distance between the doc and the middle of q.geo.")
          aDocsSort: DocSortEnum,

          @QueryParam("a.time.limit") @DefaultValue("0") @Min(0) @Max(1000)
          @ApiParam("Non-0 triggers time/date range faceting. This value is the" +
                  " maximum number of time ranges to return when a.time.gap is unspecified." +
                  " This is a soft maximum; less will usually be returned. A suggested value is" +
                  " 80.  If too many bars are requested, the performance may suffer greatly." +
                  " Note that setting a.time.gap will ignore this value." +
                  " See Solr docs for more details on the query/response format." +
                  " The counts DO NOT ignore the q.time filter if present.")
          aTimeLimit: Int,

          @QueryParam("a.time.gap") @Pattern(regexp = """P((\d+[YMWD])|(T\d+[HMS]))""")
          @ApiParam("The consecutive time interval/gap for each time range.  If blank, then " +
                  "it will default to the smallest meaningful unit of time that will produce " +
                  "fewer than a.time.limit counts.  " +
                  "The format is based on a subset of the ISO-8601 duration format.", example = "P1D")
          aTimeGap: String?,

          @QueryParam("a.time.filter") @Pattern(regexp = """\[(\S+) TO (\S+)\]""")
          @ApiParam("From what time range to divide by a.time.gap into intervals.  Defaults to" +
                  " q.time and otherwise 90 days.")
          aTimeFilter: String?,

          @QueryParam("a.hm.limit") @DefaultValue("0") @Min(0) @Max(10000)
          @ApiParam("Non-0 triggers heatmap/grid faceting.  This number is a soft maximum on the" +
                  "number of cells it should have." +
                  " There may be as few as 1/4th this number in return.  Note that a.hm.gridLevel" +
                  " can effectively ignore this value." +
                  " The response heatmap contains a counts grid that can be null or contain null" +
                  " rows when all its values would be 0.  " +
                  " See Solr docs for more details on the response format." +
                  " The counts ignore the q.geo filter if present.")
          aHmLimit: Int,

          @QueryParam("a.hm.gridLevel") @Min(1) @Max(100)
          @ApiParam("To explicitly specify the grid level, e.g. to let a user ask for greater or" +
                  " courser resolution than the most recent request.  Ignores a.hm.limit.")
          aHmGridLevel: Int?,

          @QueryParam("a.hm.filter") @Pattern(regexp = """\[(\S+,\S+) TO (\S+,\S+)\]""")
          @ApiParam("From what region to plot the heatmap. Defaults to q.geo or otherwise the" +
                  " world.")
          aHmFilter: String?,

          @QueryParam("a.hm.posSent") @DefaultValue("false")
          @ApiParam("If true, an additional heatmap grid is returned for positive sentiment tweets")
          aHmPosSent: Boolean,

          @QueryParam("a.text.limit") @DefaultValue("0") @Min(0) @Max(1000)
          @ApiParam("Returns the most frequently occurring words.  WARNING: There is usually a" +
                  " significant performance hit in this due to the extremely high cardinality.")
          aTextLimit: Int,

          @QueryParam("a.user.limit") @DefaultValue("0") @Min(0) @Max(1000)
          @ApiParam("Returns the most frequently occurring users." +
                  " The counts ignore the q.user filter if present.")
          aUserLimit: Int

  ): SearchResponse {
    // note: The DropWizard *Param classes have questionable value with Kotlin given null types so
    //  we don't use them

    val solrQuery = SolrQuery()
    solrQuery.requestHandler = "/select/bop/search"

    qConstraints.applyToSolrQuery(solrQuery)

    // a.docs
    requestDocs(aDocsLimit, aDocsSort, qConstraints, solrQuery)

    // Aggregations/Facets

    // a.time
    if (aTimeLimit > 0) {
      requestTimeFacet(aTimeLimit, aTimeFilter ?: qConstraints.qTime, aTimeGap, solrQuery)
    }

    // a.hm
    if (aHmLimit > 0) {
      requestHeatmapFacet(aHmLimit, aHmFilter ?: qConstraints.qGeo, aHmGridLevel, aHmPosSent, solrQuery)
    }

    // a.text
    if (aTextLimit > 0) {
      requestFieldFacet(TEXT_FIELD, aTextLimit, solrQuery, exFilter = false)
    }

    // a.user
    if (aUserLimit > 0) {
      requestFieldFacet(USER_FIELD, aUserLimit, solrQuery)
    }

    // -- EXECUTE

    // We can route this request to where the realtime shard is for stability/consistency and
    //  since that machine is different than the others
    //solrQuery.set("_route_", "realtime") TODO
    solrQuery.add("debug", "timing")

    solrQuery.add(overrideSolrParams) // override params will take precedence

    val solrResp: QueryResponse
    try {
      solrResp = solrClient.query(solrQuery)
    } catch(e: SolrException) {
      throw WebApplicationException(e)
    }

    return SearchResponse(
            aMatchDocs = solrResp.results.numFound,
            // if didn't ask for docs, we return no list at all
            dDocs = if (solrQuery.rows > 0) solrResp.results.map { docToMap(it) } else null,
            aTime = SearchResponse.TimeFacet.fromSolr(solrResp),
            aHm = SearchResponse.HeatmapFacet.fromSolr(solrResp, GEO_HEATMAP_FIELD),
            aHmPosSent = SearchResponse.HeatmapFacet.fromSolr(solrResp, GEO_POS_SENT_HEATMAP_FIELD),
            aText = SearchResponse.fieldValsFacetFromSolr(solrResp, TEXT_FIELD),
            aUser = SearchResponse.fieldValsFacetFromSolr(solrResp, USER_FIELD),
            timing = SearchResponse.getTimingFromSolr(solrResp)
    )
  }

  private fun requestDocs(aDocsLimit: Int, aDocsSort: DocSortEnum, qConstraints: ConstraintsParams, solrQuery: SolrQuery) {
    solrQuery.rows = aDocsLimit
    if (solrQuery.rows == 0) {
      return
    }
    // Set FL:
    //solrQuery.setFields(FL_PARAM)

    // Set Sort:
    val sort = if (aDocsSort == DocSortEnum.score && qConstraints.qText == null) {//score requires query string
      DocSortEnum.time // fall back on time even if asked for score (should we error instead?)
    } else {
      aDocsSort
    }
    when (sort) {
      DocSortEnum.score -> solrQuery.addSort("score", SolrQuery.ORDER.desc)
      //TODO also sort by time after score?

      DocSortEnum.time -> solrQuery.addSort(TIME_SORT_FIELD, SolrQuery.ORDER.desc)

      DocSortEnum.distance -> {
        solrQuery.addSort("geodist()", SolrQuery.ORDER.asc)
        solrQuery.set("sfield", GEO_SORT_FIELD)
        val distPoint = qConstraints.qGeoRect?.center
                ?: throw WebApplicationException("Can't sort by distance without q.geo", 400)
        solrQuery.set("pt", toLatLon(distPoint.center))
      }
    }
  }

  private fun requestTimeFacet(aTimeLimit: Int, aTimeFilter: String?, aTimeGap: String?, solrQuery: SolrQuery) {
    val now = Instant.now()
    val (_startInst, _endInst) = parseDateTimeRange(aTimeFilter)
    val startInst = _startInst ?: now.minus(90, ChronoUnit.DAYS)
    val endInst = _endInst ?: now

    val rangeDuration = Duration.between(startInst, endInst)
    if (rangeDuration.isNegative) {
      throw WebApplicationException("date ordering problem: $aTimeFilter", 400)
    }

    val gap = when (aTimeGap) {
      null -> Gap.computeGap(rangeDuration, aTimeLimit)
      else -> Gap.parseISO8601(aTimeGap)
    }

    // verify the gap provided won't have too many bars
    val numBarsEstimate = rangeDuration.toMillis() / gap.toMillis()
    if (numBarsEstimate > 1000) {
      throw WebApplicationException("Gap $aTimeGap is too small for this range $aTimeFilter")
    }
    // avoid blowing the filter cache by having too many bars
    if (numBarsEstimate > 80 && solrQuery.get(FacetParams.FACET_RANGE_METHOD) != null) {
      // TODO disable the filterCache purely for this facet range; file JIRA issue
      log.info("Too many bars requested ($numBarsEstimate), switching to facet.range.method=dv")
      solrQuery.set(FacetParams.FACET_RANGE_METHOD, "dv")
    }

    solrQuery.apply {
      set(FacetParams.FACET, true)
      // TODO {!ex=$TIME_FILTER_FIELD}  however how might that effect hcga.start/end routing params...
      //   Hmm; maybe Solr is smart enough to short-circuit faceting when docset base is empty.
      val field = if (solrQuery.get(FacetParams.FACET_RANGE_METHOD) == "dv") TIME_FILTER_DV_FIELD else TIME_FILTER_FIELD
      add(FacetParams.FACET_RANGE,
              "{!key=a.time " +
                      "${FacetParams.FACET_RANGE_START}=$startInst " +
                      "${FacetParams.FACET_RANGE_END}=$endInst " +
                      "${FacetParams.FACET_RANGE_GAP}=${gap.toSolr()} " +
                      //"${FacetParams.FACET_MINCOUNT}=0 " + // distributed search bug; won't work?
                      "}$field")
      set("f.a.time.${FacetParams.FACET_MINCOUNT}", 0) // work-around Solr bug for facet local-params and distrib
      set("f.$field.${FacetParams.FACET_MINCOUNT}", 0) // work-around Solr bug for facet local-params and distrib
    }
  }

  private fun requestHeatmapFacet(aHmLimit: Int, aHmFilter: String?, aHmGridLevel: Int?,
                                  aHmPosSent: Boolean, solrQuery: SolrQuery) {
    solrQuery.setFacet(true)
    // Note: if the heatmap geom is the same as (or within) the filter geom, there's no need for
    //  faceting to exclude it (will waste a filter cache entry and must be calculated).
    // However, by excluding it we increase the possibility that Solr's docSet will
    //  match everything which will be a large optimization. (Solr 6.6)
    //val ex = if (aHmFilter == null || solrQuery.filterQueries.any { it.contains(aHmFilter) }) "" else "{!ex=$GEO_FILTER_FIELD}"
    val ex = "{!ex=$GEO_FILTER_FIELD}"
    solrQuery.set(FacetParams.FACET_HEATMAP, "$ex$GEO_HEATMAP_FIELD")
    if (aHmPosSent) {
      solrQuery.add(FacetParams.FACET_HEATMAP, "$ex$GEO_POS_SENT_HEATMAP_FIELD")
    }
    // note: all options below apply to all heatmaps
    val hmRectStr = aHmFilter ?: "[-90,-180 TO 90,180]"
    solrQuery.set(FacetParams.FACET_HEATMAP_GEOM, hmRectStr)
    if (aHmGridLevel != null) {
      // note: aHmLimit is ignored in this case
      solrQuery.set(FacetParams.FACET_HEATMAP_LEVEL, aHmGridLevel)
    } else {
      // Calculate distErr that will approximate aHmLimit many cells as an upper bound
      val hmRect: Rectangle = hmRectStr.parseGeoBox()
      if (!hmRect.hasArea()) {
        throw WebApplicationException("Can't compute heatmap; the rect geom has no area: $hmRectStr")
      }
      val degreesSideLen = (hmRect.width + hmRect.height) / 2.0 // side len of square (in degrees units)
      val cellsSideLen = Math.sqrt(aHmLimit.toDouble()) // side len of square (in target cell units)
      val cellSideLenInDegrees = degreesSideLen / cellsSideLen * 2.0
      // Note: the '* 2' is complicated.  Basically distErr is a maximum error (actual error may
      //   be smaller). This has the effect of choosing the minimum number of cells for a target
      //   resolution.  So *2 assumes quad tree (double side length to next level)
      //   and will tend to choose a more coarse level.
      // Note: assume units="kilometers" on this field type
      val cellSideLenInKm = cellSideLenInDegrees * DistanceUtils.DEG_TO_KM
      solrQuery.set(FacetParams.FACET_HEATMAP_DIST_ERR, cellSideLenInKm.toFloat().toString())
    }
  }

  private fun requestFieldFacet(field: String, limit: Int, solrQuery: SolrQuery, exFilter: Boolean = true) {
    solrQuery.setFacet(true)
    solrQuery.add("facet.field", if (exFilter) "{! ex=$field}$field" else field)
    solrQuery.set("f.$field.facet.limit", limit)
    // we let params on the Solr side tune this further if desired
  }

  private fun docToMap(doc: SolrDocument): Map<String, Any> {
    val map = LinkedHashMap<String, Any>()
    for ((name, value) in doc) {
      if (name[0] == '_') { // e.g. _version_
        continue;
      }
      val newValue = when (name) {
        // convert id original twitter id
        ID_FIELD -> solrIdToTweetId(value)
        else -> value
      }
      map.put(name, newValue)
    }
    return map
  }

  // Tweet ID (unsigned Long) on ingest is mapped to a Java Long (signed).  Here we reverse that.
  private fun solrIdToTweetId(value: Any): String = java.lang.Long.toUnsignedString(value as Long)

  @JsonPropertyOrder("a.matchDocs", "d.docs", "a.time", "a.hm", "a.hm.posSent", "a.user", "a.text", "timing")
  data class SearchResponse (
          @get:JsonProperty("a.matchDocs") val aMatchDocs: Long,
          @get:JsonProperty("d.docs") val dDocs: List<Map<String,Any>>?,
          @get:JsonProperty("a.time") val aTime: TimeFacet?,
          @get:JsonProperty("a.hm") val aHm: HeatmapFacet?,
          @get:JsonProperty("a.hm.posSent") val aHmPosSent: HeatmapFacet?,
          @get:JsonProperty("a.user") val aUser: List<FacetValue>?,
          @get:JsonProperty("a.text") val aText: List<FacetValue>?,
          @get:JsonProperty("timing") val timing: Timing
  ) {

    companion object {
      fun fieldValsFacetFromSolr(solrResp: QueryResponse, field: String): List<FacetValue>? {
        val facetField: FacetField = solrResp.getFacetField(field) ?: return null
        return facetField.values.map { FacetValue(it.name, it.count.toLong()) }
      }

      @Suppress("UNCHECKED_CAST")
      fun getTimingFromSolr(solrResp: QueryResponse): Timing {
        val tree = convertSolrTimingTree("QTime", solrResp.debugMap["timing"] as NamedList<Any>)
        // I don't understand why the different QTime's vary
        if (tree != null && Math.abs(solrResp.qTime.toLong() - tree.millis) > 5) {
          log.debug("QTime != debug.timing.time: ${solrResp.qTime} ${tree.millis}")
        }
        return Timing("callSolr.elapsed", solrResp.elapsedTime, listOfNotNull(tree));
      }

      @Suppress("UNCHECKED_CAST")
      private fun convertSolrTimingTree(label: String, namedListOrLong: Any): Timing? {
        if (namedListOrLong is NamedList<*>) {
          val namedList = namedListOrLong
          val millis = (namedList.remove("time") as Double).toLong() // note we remove it
          if (millis == 0L) { // avoid verbosity; lots of 0's is typical
            return null
          }
          val subs = namedList.map { convertSolrTimingTree(it.key, it.value) }
          return Timing(label, millis, subs.filterNotNull())
        } else if (namedListOrLong is Long) { // atypical
          val millis = namedListOrLong
          return if (millis != 0L) { Timing(label, millis) } else null
        } else {
          log.warn("Unexpected timing for label $label: $namedListOrLong")
          return null
        }
      }
    }

    data class FacetValue(val value: String, val count: Long)

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    data class Timing(val label: String, val millis: Long, val subs: List<Timing> = emptyList())

    data class TimeFacet( // TODO document/fix Date type
            val start: String,//Date
            val end: String,//Date
            val gap: String,
            val counts: List<FacetValue>
    ) {
      companion object {
        fun fromSolr(solrResp: QueryResponse): TimeFacet? {
          val rng = solrResp.facetRanges?.firstOrNull { it.name == "a.time" } ?: return null
          return TimeFacet(
                  start = (rng.start as Date).toInstant().toString(),
                  end = (rng.end as Date).toInstant().toString(),
                  gap = Gap.parseSolr(rng.gap as String).toISO8601(),
                  counts = rng.counts.map { FacetValue(it.value, it.count.toLong()) }
          )
        }
      }
    }

    data class HeatmapFacet(
            val gridLevel: Int,
            val rows: Int,
            val columns: Int,
            val minX: Double, val maxX: Double, val minY: Double, val maxY: Double,
            //TODO api document implication of nulls in this grid
            val counts_ints2D: List<List<Int>>?,
            val projection: String
    ) {
        companion object {
          @Suppress("UNCHECKED_CAST")
          fun fromSolr(solrResp: QueryResponse, fieldName: String): HeatmapFacet? {
            val hmNl = solrResp.response
                    .findRecursive("facet_counts", "facet_heatmaps", fieldName) as NamedList<Any>?
                    ?: return null
            // TODO consider doing this via a reflection utility; must it be Kotlin specific?
            return HeatmapFacet(gridLevel = hmNl.get("gridLevel") as Int,
                    rows = hmNl.get("rows") as Int,
                    columns = hmNl.get("columns") as Int,
                    minX = hmNl.get("minX") as Double,
                    maxX = hmNl.get("maxX") as Double,
                    minY = hmNl.get("minY") as Double,
                    maxY = hmNl.get("maxY") as Double,
                    counts_ints2D = hmNl.get("counts_ints2D") as List<List<Int>>?,
                    projection = "EPSG:4326") // wgs84.  TODO switch to web mercator?
          }
        }
    }

  }// class SearchResponse

  enum class DocSortEnum {score, time, distance}

  @Path("/export")
  @ApiOperation(value = "Search export endpoint for bulk doc retrieval.",
                notes = """The q.* parameters are query/constraints that limit the
                matching documents. Documents come back sorted by time descending.
                The response format is text/csv -- comma separated.  There is a header row.
                Values are enclosed in
                double-quotes (") if it contains a double-quote, comma, or newline.  Embedded
                double-quotes are escaped with another double-quote, thus: foo"bar becomes
                "foo""bar". """)
  @GET
  @Produces("text/json") // TODO HACK!  Lie so that validation errors can be mapped to 400
  @Timed
  fun export(@BeanParam
             qConstraints: ConstraintsParams,

             @QueryParam("d.docs.limit") @Min(1) @Max(10000) // TODO vary based on authentication?
             @ApiParam("How many documents to return.")
             aDocsLimit: Int
  ): Response {
    val solrQuery = SolrQuery()
    solrQuery.requestHandler = "/select/bop/export"

    qConstraints.applyToSolrQuery(solrQuery)

    requestDocs(aDocsLimit, DocSortEnum.time, qConstraints, solrQuery)

    solrQuery.set("echoParams", "all") // so that we can read 'fl' (we configured Solr to have this)

    val solrResp: QueryResponse
    try {
      solrResp = solrClient.query(solrQuery);
    } catch(e: SolrException) {
      throw WebApplicationException(e.message, e.code()) // retain http code
    }

    // assume this echo's params on the server to include 'fl' (we arranged for this in solrconfig)
    val flStr = (solrResp.header.findRecursive("params", "fl")
            ?: throw Exception("Expected echoParams=all and 'fl' to be set")) as String
    val fieldList = flStr.split(',').map(String::trim).toList()

    val streamingOutput = StreamingOutput { outputStream: OutputStream ->
      val writer = outputStream.writer() //defaults to UTF8 in Kotlin
      fun OutputStreamWriter.writeEscaped(str: String)
              = StringEscapeUtils.ESCAPE_CSV.translate(str, this) // commons-lang3

      // write header
      for ((index, f) in fieldList.withIndex()) {
        if (index != 0) writer.write(','.toInt())
        writer.writeEscaped(f)
      }
      writer.write('\n'.toInt())

      // loop docs
      for (doc in solrResp.results) {
        val map = docToMap(doc)
        // write doc
        for ((index, f) in fieldList.withIndex()) {
          if (index != 0) writer.write(','.toInt())
          map[f]?.let {
            if (it is List<*>) { // multi-valued
              writer.writeEscaped(it.joinToString("|"))
            } else { // single-value
              writer.writeEscaped(it.toString())
            }
          }
        }
        writer.write('\n'.toInt())
      }
      writer.flush()
    }

    return Response.ok(streamingOutput, "text/csv;charset=utf-8") // TODO or JSON eventually
            .header("Content-Disposition", "attachment") // don't show in-line in browser
            .build()

    // TODO use Solr StreamingResponseCallback. Likely then need another thread & Pipe

    // TODO "Content-Disposition","attachment"
    // TODO support JSON (.json in path?)
    // TODO support Solr cursorMark
    // TODO only let one request do this at a time (metered, or perhaps with authorization)
    // TODO iterate the shards in time descending instead of all at once

  }

}
