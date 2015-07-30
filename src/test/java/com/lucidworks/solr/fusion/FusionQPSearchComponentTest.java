package com.lucidworks.solr.fusion;

import junit.framework.TestCase;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

import java.util.ArrayList;

public class FusionQPSearchComponentTest extends TestCase {
  public void testFusionResponseProcessing() throws Exception {
    String typicalFusionResponse = "{ \n" +
        "   \"fusion\" :  {\n" +
        "         \"query-params\":  {\n" +
        "              \"extra\": [\"test\"],\n" +
        "              \"extrarray\": [\"test1\",\"test2\"]\n" +
        "         },\n" +
        "         \"landing-pages\":  [\n" +
        "              \"www.lucidworks.com\"\n" +
        "         ]\n" +
        "    }\n" +
        "}";


    SolrQueryRequest req = new LocalSolrQueryRequest(null, new NamedList());
    SolrQueryResponse rsp = new SolrQueryResponse();

    FusionQPSearchComponent.overlayFusionData(typicalFusionResponse, req, rsp);

    // test query params made it to the request
    SolrParams p = req.getParams();
    assertEquals("test", p.getParams("extra")[0]);
    assertEquals("test1", p.getParams("extrarray")[0]);
    assertEquals("test2", p.getParams("extrarray")[1]);

    // check that everything else passed through into the response
    NamedList nl = rsp.getValues();
    ArrayList landingPages = (ArrayList)((NamedList)nl.get("fusion")).get("landing-pages");
    assertNotNull(landingPages);
    assertEquals("www.lucidworks.com", landingPages.get(0));
  }
}
