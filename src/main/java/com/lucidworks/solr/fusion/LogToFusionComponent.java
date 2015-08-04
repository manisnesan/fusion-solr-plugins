package com.lucidworks.solr.fusion;


import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class LogToFusionComponent extends SearchComponent{

  protected static Logger log = LoggerFactory.getLogger(LogToFusionComponent.class);
  public static final String IS_FUSION_QUERY_PARAM = "isFusionQuery";

  private static ObjectMapper objectMapper;
  private NamedList initParams;
  private HttpClient httpClient;
  private String fusionBaseUrl;
  private String collectionName;

  //default values:
  int soTimeout = 0;
  int connectionTimeout = 0;
  int maxConnectionsPerHost = 20;

  @Override
  public void init(NamedList args) {
    // Object mapper and client for querying Fusion and parsing the JSON response
    objectMapper = new ObjectMapper();
    ModifiableSolrParams clientParams = new ModifiableSolrParams();
    clientParams.set(HttpClientUtil.PROP_MAX_CONNECTIONS_PER_HOST, maxConnectionsPerHost);
    clientParams.set(HttpClientUtil.PROP_MAX_CONNECTIONS, 10000);
    clientParams.set(HttpClientUtil.PROP_SO_TIMEOUT, soTimeout);
    clientParams.set(HttpClientUtil.PROP_CONNECTION_TIMEOUT, connectionTimeout);
    clientParams.set(HttpClientUtil.PROP_USE_RETRY, false);
    this.httpClient = HttpClientUtil.createClient(clientParams);
    this.initParams = args;

    if (args != null) {
      if (args.get("fusion_base_url") != null) {
        fusionBaseUrl = String.valueOf(args.get("fusion_base_url"));
      }
      if (args.get("collection_name") != null) {
        collectionName = String.valueOf(args.get("collection_name"));
      }
    }

    if (fusionBaseUrl == null) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
        "config \'fusion_base_url\' is missing from searchComponent configuration");
    }

    if (collectionName == null) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
        "config \'collection_name\' is missing from search component configuration");
    }
  }

  @Override
  public void prepare(ResponseBuilder rb) throws IOException {
    // Check if logging should be done or not by checking a query-param ?
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {

    Boolean isFusionQuery = rb.req.getParams().getBool(IS_FUSION_QUERY_PARAM);
    if (isFusionQuery != null) {
      if (isFusionQuery) {
        log.info("Skipping the logging to Fusion as this is a Fusion query");
        return;
      }
    }

    SolrParams params = rb.req.getParams();
    boolean isShard = params.getBool(ShardParams.IS_SHARD, false);
    if (isShard) {
      return;
    }
    
    sendSearchLogsToFusion(rb, false);
  }

  @Override
  public String getDescription() {
    return null;
  }

  @Override
  public String getSource() {
    return null;
  }

  @Override
  public void finishStage(ResponseBuilder rb) {


    SolrParams params = rb.req.getParams();

    boolean isShard = params.getBool(ShardParams.IS_SHARD, false);
    if (isShard) {
      return;
    }

    Boolean isFusionQuery = params.getBool(IS_FUSION_QUERY_PARAM);
    if (isFusionQuery != null) {
      if (isFusionQuery) {
        log.info("Skipping the logging to Fusion as this a Fusion query");
        return;
      }
    }

    if (rb.stage != ResponseBuilder.STAGE_GET_FIELDS) {
      return;
    }

    sendSearchLogsToFusion(rb, true);
  }

  // stolen from Solr's SpellCheckComponent
  private static long getNumDocsNonDistrib(ResponseBuilder rb) {
    long hits = 0;
    Object obj = rb.rsp.getToLog().get("hits");
    if (obj != null && obj instanceof Integer) {
      Integer hitsInteger = (Integer) obj;
      hits = hitsInteger.longValue();
    } else {
      hits = rb.getNumberDocumentsFound();
    }
    return hits;
  }

  // stolen from Solr's SpellCheckComponent
  private static long getNumDocsDistrib(ResponseBuilder rb) {
    return rb.grouping() ? rb.totalHitCount : rb.getNumberDocumentsFound();
  }

  private static long getQTime(ResponseBuilder rb) {
    return System.currentTimeMillis() - rb.req.getStartTime();
  }


  private void sendSearchLogsToFusion(ResponseBuilder rb,
                                      boolean isDistrib) {
    SolrQueryRequest req = rb.req;

    // Allow over-writes via query-param
    if (rb.req.getParams().get("fusion_base_url") != null) {
      fusionBaseUrl = rb.req.getParams().get("fusion_base_url");
    }

    String fusionUrl = fusionBaseUrl + "/searchLogs/" + collectionName + "/searchlog";

    // create the searchEvent
    Map<String, Object> searchEvent = new HashMap<String, Object>();
    if (isDistrib) {
      searchEvent.put("numFound", getNumDocsDistrib(rb));
    } else {
      searchEvent.put("numFound", getNumDocsNonDistrib(rb));
    }
    searchEvent.put("QTime", getQTime(rb));
    searchEvent.put("queryParams", toMultiMap(req.getParams()));


    HttpPost httpPost = new HttpPost(fusionUrl);
    String searchEventAsString = null;

    try {
      searchEventAsString = objectMapper.writeValueAsString(searchEvent);
    } catch (IOException e) {
      log.warn("IOException while converting search event to a string", e);
    }

    try {
      if (searchEventAsString != null) {
        httpPost.setEntity(new StringEntity(searchEventAsString));
        log.info("The search event is " + searchEventAsString);
      } else {
        log.warn("The search event is null. Skipping logging.");
        return;
      }
    } catch (UnsupportedEncodingException e) {
      log.error("Error while creating the entity" + searchEventAsString);
    }

    httpPost.setHeader("Content-type", "application/json");
    try {
      log.debug("Executing query " + httpPost.toString() + " with entity " + searchEventAsString);
      HttpResponse response = httpClient.execute(httpPost);
      if (response.getStatusLine().getStatusCode() > 300) {
        log.warn("unsuccessful request with a status code of " + response.getStatusLine().getStatusCode() + " to"
          + fusionUrl + " with entity " + searchEventAsString + " failed with response " +
          EntityUtils.toString(response.getEntity()));
      }
    }  catch (Exception e) {
      log.warn("Exception when indexing search log event to Fusion at url " + fusionUrl, e);
    }
  }


  /** Create a Map&lt;String,String[]&gt; from {@link org.apache.solr.common.params.SolrParams}*/
  public static Map<String, String[]> toMultiMap(SolrParams solrParams) {
    HashMap<String,String[]> multiValuedMap = new HashMap<String, String[]>();
    Iterator<String> params = solrParams.getParameterNamesIterator();
    while (params.hasNext()) {
      String param = params.next();
      String[] values = solrParams.getParams(param);
      if (values != null) {
        multiValuedMap.put(param, values);
      }
    }
    return multiValuedMap;
  }

}
