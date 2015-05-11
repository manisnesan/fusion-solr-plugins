package com.lucidworks.solr.fusion;


import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;

public class FusionUpdateProcessorFactory extends UpdateRequestProcessorFactory{

  private SolrParams params;

  @Override
  public void init(@SuppressWarnings("rawtypes") final NamedList args) {
    if (args != null) {
      this.params = SolrParams.toSolrParams(args);
    }
  }

  @Override
  public UpdateRequestProcessor getInstance(SolrQueryRequest solrQueryRequest, SolrQueryResponse solrQueryResponse, UpdateRequestProcessor updateRequestProcessor) {
    return new FusionUpdateProcessor(params, solrQueryRequest, solrQueryResponse, updateRequestProcessor);
  }
}
