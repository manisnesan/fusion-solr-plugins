# fusion-solr-plugins
Plugins for Solr to communicate with Fusion query and index pipelines. Solr plugins approach should be used when direct
integration of the application with Fusion query and index pipelines is not possible (WebSphere, Hybris).
 
The plugins are located inside the jar in `$FUSION_HOME/solr-plugins/fusion`. The jar file is compiled with Oracle JDK 1.6.

Downloadable Jar
=================

Download the latest jar file from [releases](https://github.com/LucidWorks/fusion-solr-plugins/releases)

Build jar files locally
=============

  * Dependencies:
    * JDK 1.6+
  * `./gradlew fatJar`

Create the search cluster and import the Solr collection to Fusion
==================================================================

* Create a search cluster for the Solr instance inside Fusion
* Then import the collection using the searchCluster name inside the Fusion Admin collections API

Configuring Fusion query-pipelines for the plugins
==================================================

* The query-pipelines should be modified so that they can work with the Solr searchComponents below. 
    * Add the conditional `!request.hasParam("isFusionPluginQuery")` to the solr-query stage in the pipeline
    * Add the Return query-params stage at the end of your pipeline.
    
      **Example**:
      
            {
              "id" : "MC_10001_CatalogEntry_en_US-default",
              "stages" : [ {
                "type" : "search-fields",
                "id" : "1138f220-4c55-4314-ae2c-ea480c95b077",
                "rows" : 10,
                "start" : 0,
                "skip" : false,
                "label" : "search-fields",
                "type" : "search-fields"
              }, {
                "type" : "facet",
                "id" : "e371277a-2d2a-4730-89c5-0725b66da656",
                "skip" : false,
                "label" : "facet",
                "type" : "facet"
              }, {
                "type" : "solr-query",
                "id" : "10c39b09-a28e-4b1b-9d93-9136d6c1bb16",
                "skip" : false,
                "label" : "solr-query",
                "condition" : "!request.hasParam(\"isFusionPluginQuery\")",
                "type" : "solr-query"
              }, {
                "type" : "return-queryparams",
                "id" : "er4l5wmi",
                "skip" : false,
                "label" : "return-queryparams",
                "type" : "return-queryparams"
              } ]
            }

Configure solrconfig.xml to use Fusion Query Pipelines
===========================================================

* Add the jar file location to the `solrconfig.xml` config file for the collection that you would like to use.

        <lib dir="$FUSION_HOME/solr-plugins/fusion"/>

* Add the searchComponents `FusionQPSearchComponent` and `LogToFusionComponent` to the solrconfig.xml file. The two parameters that the component requires are 'fusion_base_url' and 'collection_name'. 

   * FusionQPSearchComponent:
        This component queries Fusion query-profiles to get the updated list of query-params. This search component queries the default 
        query-profile and due to this, the pipeline used by the query-profile can be changed anytime through the Fusion Admin UI. Read the section `Configuring Fusion query-pipelines for the plugins` on how to configure query-pipelines for this plugin
   
            <searchComponent name="fusionQuery" class="com.lucidworks.solr.fusion.FusionQPSearchComponent" >
               <str name="fusion_base_url">http://admin:password123@localhost:8764/api/apollo</str> 
               <str name="collection_name">{collection_name}</str> 
            </searchComponent>
          
   * LogToFusionComponent:
        This component logs all the queries for the Solr collection through the Fusion searchLogs feature. All the logged queries can be visualized through the Banana dashboards.
    
            <searchComponent name="logToFusion" class="com.lucidworks.solr.fusion.LogToFusionComponent">
               <str name="fusion_base_url">http://admin:password123@localhost:8764/api/apollo</str>
               <str name="collection_name">{collection_name}</str>
            </searchComponent>
             
* Configure the search request handler to use the search components. `FusionQPSearchComponent` should always be first 
 and `LogToFusionComponent` should be last.

          <requestHandler name="search" class="solr.SearchHandler" default="true">
            <!-- default values for query parameters can be specified, these
                 will be overridden by parameters in the request
              -->
        
            <arr name="first-components">
                <str>fusionQuery</str>
            </arr>
        
            <arr name="last-components">
                <str>logToFusion</str>
            </arr>
          </requestHandler>

* Reload your collection and all the queries to the configured collection should go through Fusion query-pipelines to 
get query params, be logged. If the component failed to connect to Fusion or if there are some 
processing errors, then the main query is executed as normal.

Configure Solr DIH to use Fusion Index Pipelines
================================================

1. Add the jar file location to the `solrconfig.xml` config file for the collection that you would like to use.

        <lib dir="$FUSION_HOME/solr-plugins/fusion"/>

2. Add the update processor `FusionUpdateProcessorFactory` to the solrconfig.xml
    
         <updateRequestProcessorChain name="sendDocsToFusion">
            <processor class="com.lucidworks.solr.fusion.FusionUpdateProcessorFactory">
                 <bool name="enabled">true</bool>
                 <str name="fusion_base_url">http://admin:password123@localhost:8764/api/apollo</str>
                 <str name="collection_name">{collection_name}</str>
            </processor>
            <processor class="solr.LogUpdateProcessorFactory" />
            <processor class="solr.RunUpdateProcessorFactory" />
          </updateRequestProcessorChain>

3. Configure the DIH request handler with the update chain

          <requestHandler name="/dataimport" class="org.apache.solr.handler.dataimport.DataImportHandler">
            <lst name="defaults">
                <str name="config">wc-data-config.xml</str>
              <str name="update.chain">sendDocsToFusion</str>
                <!-- Enable if using dynamic spellCheck field. 
                <str name="update.chain">wc-conditionalCopyFieldChain</str> 
                -->
            </lst>
          </requestHandler>

4. **Note**: This is a very slow process since each doc is sent to Fusion one by one.
