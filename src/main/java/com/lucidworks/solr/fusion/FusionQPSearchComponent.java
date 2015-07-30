package com.lucidworks.solr.fusion;


import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The class {@code FusionQPSearchComponent} is a SearchComponent used to
 * query Fusion query-pipelines to retrieve a JSON payload of query-params and process the query.
 *
 * The query to Fusion can be disabled by setting 'doFusionQuery' to
 * false in solrconfig.xml or dynamically via query-params
 *
 * In case of un-successful request or a bad response format, the original query is processed.
 */
public class FusionQPSearchComponent extends SearchComponent{

  protected static Logger log = LoggerFactory.getLogger(FusionQPSearchComponent.class);
  public static final String IS_FUSION_QUERY_PARAM = "isFusionQuery";


  private static ObjectMapper objectMapper;
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
  public void prepare(ResponseBuilder responseBuilder) throws IOException {

    SolrQueryRequest req = responseBuilder.req;

    boolean isShard = req.getParams().getBool(ShardParams.IS_SHARD, false);
    if (isShard) {
      return;
    }

    // Allow over-writes via query-param
    if (req.getParams().get("fusion_base_url") != null) {
     fusionBaseUrl = req.getParams().get("fusion_base_url");
    }

    // TODO: we need a test for doFusionQuery, and doc
    Boolean queryFusionForParams = req.getParams().getBool("doFusionQuery");
    if (queryFusionForParams != null) {
      if (!queryFusionForParams) {
        log.info("Skipping the query to Fusion. Value of 'doFusionQuery' is " + queryFusionForParams);
        return;
      }
    }

    Boolean isFusionQuery = req.getParams().getBool(IS_FUSION_QUERY_PARAM);
    if (isFusionQuery != null) {
      if (isFusionQuery) {
        log.info("Skipping the query to Fusion as this a Fusion query");
        return;
      }
    }

    try {
      log.info("Querying Fusion to get pipeline params and data");
      processPipelineDataFromFusion(fusionBaseUrl, collectionName, req, responseBuilder.rsp);
    } catch (Exception e) {
      log.warn("Exception while querying Fusion for query-params. Continuing with the original params");
      e.printStackTrace();
    }

  }

  @Override
  public void process(ResponseBuilder responseBuilder) throws IOException {

  }

  @Override
  public String getDescription() {
    return "A search component that uses Fusion query-pipelines to retrieve params and data";
  }

  @Override
  public String getSource() {
    return null;
  }

  /**
   * Get the query-params from Fusion query-pipelines by doing a POST request.
   * The API endpoint to get query-params from a query-pipeline is
   * -X POST http://localhost:8764/api/apollo/collections/{collection_name}/query-profiles/{pipeline_name}/{handler_name}
   *
   * In case of an exception while querying or parsing Fusion,
   * do not modify the query-params and continue with the original request.
   */
  private void processPipelineDataFromFusion(String fusionBaseUrl,
                                        String collectionId,
                                        SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {

    String fusionUrl = fusionBaseUrl + "/collections/" + collectionId + "/query-profiles/default/select";
    HttpPost httpPost = new HttpPost(fusionUrl);
    List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();

    Iterator it = req.getParams().getParameterNamesIterator();
    while (it.hasNext()) {
      String key = (String) it.next();
      String[] values = req.getParams().getParams(key);
      if (values != null) {
        for (String value: values) {
          urlParameters.add(new BasicNameValuePair(key, value));
        }
      }
    }
    // Add a hard-coded request param to identify requests coming through this plugin
    urlParameters.add(new BasicNameValuePair("isFusionPluginQuery", "true"));

    httpPost.setEntity(new UrlEncodedFormEntity(urlParameters));
    httpPost.addHeader("Content-Type",
      "application/x-www-form-urlencoded; charset=UTF-8");

    // Create a custom response handler
    ResponseHandler<String> responseHandler = new ResponseHandler<String>() {

      public String handleResponse(
        final HttpResponse response) throws IOException {
        int status = response.getStatusLine().getStatusCode();
        if (status >= 200 && status < 300) {
          HttpEntity entity = response.getEntity();
          return entity != null ? EntityUtils.toString(entity) : null;
        } else {
          throw new ClientProtocolException("Unexpected response status: " + status +
            ". Message returned is '" + EntityUtils.toString(response.getEntity()) + "'");
        }
      }
    };

    log.debug("Executing query " + httpPost.toString() + " with entity " + urlParameters);
    String fusionJSON = null;
    try {
      fusionJSON = httpClient.execute(httpPost, responseHandler);
    }  catch (Exception e) {
      log.warn("Exception " + e.toString() + " when querying Fusion at url " + fusionUrl);
    }

    try {
      if (fusionJSON != null) {
        // parse the JSON response from Fusion
        overlayFusionData(fusionJSON, req, rsp);
      }
    } catch (Exception e) {
      log.warn("Exception while reading response from Fusion url: "  + fusionUrl, e);
      e.printStackTrace();
    }
  }

  public static void overlayFusionData(String fusionJSON, SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    Map data = objectMapper.readValue(fusionJSON, Map.class);
    Map fusionData = (Map) (data.get("fusion"));
    Map queryParams = (Map) fusionData.remove("query-params");  // .remove'd to keep everything else separate

    ModifiableSolrParams newParams = new ModifiableSolrParams(req.getParams());
    for (Object key : queryParams.keySet()) {
      for (Object value : (List)queryParams.get(key)) {
        newParams.add((String) key, (String)value);
      }
    }
    req.setParams(newParams);

    NamedList fusionResponseData = new NamedList();
    for (Object key : fusionData.keySet()) {
      fusionResponseData.add(key.toString(), fusionData.get(key));
    }
    rsp.add("fusion", fusionResponseData);
  }
}
