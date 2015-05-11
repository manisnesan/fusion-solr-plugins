package com.lucidworks.solr.fusion;


import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.handler.loader.XMLLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;

public class FusionUpdateProcessor extends UpdateRequestProcessor{

  private static final String FUSION_BASE_URL_PARAM = "fusion_base_url";
  private static final String FUSION_BASE_URL_DEFAULT = "http://localhost:8765/api/v1";
  public static final String SOLR_XML_DOCUMENT = "application/vnd.solr-document";

  private  HttpClient httpClient;
  //default values:
  int soTimeout = 0;
  int connectionTimeout = 0;
  int maxConnectionsPerHost = 20;
  protected static Logger log = LoggerFactory.getLogger(FusionUpdateProcessor.class);
  private static XMLInputFactory inputFactory;


  private String collectionName;
  private String fusionBaseUrl;
  private boolean enabled = true;


  public FusionUpdateProcessor(SolrParams params,
                               SolrQueryRequest solrQueryRequest,
                               SolrQueryResponse solrQueryResponse,
                               UpdateRequestProcessor next) {
    super(next);
    this.init(params);
    inputFactory = XMLInputFactory.newInstance();
  }

  private void init(SolrParams params) {
    ModifiableSolrParams clientParams = new ModifiableSolrParams();
    clientParams.set(HttpClientUtil.PROP_MAX_CONNECTIONS_PER_HOST, maxConnectionsPerHost);
    clientParams.set(HttpClientUtil.PROP_MAX_CONNECTIONS, 10000);
    clientParams.set(HttpClientUtil.PROP_SO_TIMEOUT, soTimeout);
    clientParams.set(HttpClientUtil.PROP_CONNECTION_TIMEOUT, connectionTimeout);
    clientParams.set(HttpClientUtil.PROP_USE_RETRY, false);
    this.httpClient = HttpClientUtil.createClient(clientParams);

    if (params != null) {
      if (params.get("fusion_base_url") != null) {
        fusionBaseUrl = String.valueOf(params.get("fusion_base_url"));
      }
      if (params.get("collection_name") != null) {
        collectionName = String.valueOf(params.get("collection_name"));
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
  public void processAdd(AddUpdateCommand command) throws IOException {
    if (enabled) {
      String xmlDoc = ClientUtils.toXML(command.getSolrInputDocument());
      String fusionUrl = fusionBaseUrl + "/collections/" +  collectionName + "/index-profiles/default/index?simulate=true";

      // Send the String via HTTP POST
      HttpPost httpPost = new HttpPost(fusionUrl);
      httpPost.addHeader("Content-Type", SOLR_XML_DOCUMENT);
      httpPost.addHeader("Accept", SOLR_XML_DOCUMENT);
      httpPost.setEntity(new StringEntity(xmlDoc));



      // Create a custom response handler
      ResponseHandler<SolrInputDocument> responseHandler = new ResponseHandler<SolrInputDocument>() {

        public SolrInputDocument handleResponse(
          final HttpResponse response) throws IOException {
          int status = response.getStatusLine().getStatusCode();
          if (status >= 200 && status < 300) {
            HttpEntity entity = response.getEntity();
            InputStream inputStream = entity.getContent();
            try {
              XMLStreamReader parser = inputFactory.createXMLStreamReader(inputStream);
              parser.next(); // read the START document...
              //null for the processor is all right here
              XMLLoader loader = new XMLLoader();
              return loader.readDoc(parser);
            } catch (XMLStreamException e) {
              e.printStackTrace();
              return null;
            }

          } else {
            throw new ClientProtocolException("Unexpected response status: " + status +
              ". Message returned is '" + EntityUtils.toString(response.getEntity()) + "'");
          }
        }
      };

      log.debug("Executing query " + httpPost.toString() + " with entity " + xmlDoc);

      try {
        SolrInputDocument doc = httpClient.execute(httpPost, responseHandler);
        if (doc != null) {
          command.solrDoc = doc;
          log.debug("Updated document from Fusion " + doc.toString());
        }
      }  catch (Exception e) {
        log.warn("Exception " + e.toString() + " when querying Fusion at url " + fusionUrl + " with entity " + xmlDoc);
      }
    }

    super.processAdd(command);
  }
}
