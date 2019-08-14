package com.github.spencer19.kafka.tutorial1;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import jdk.nashorn.internal.parser.JSONParser;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

public class ConsumerElasticSearch {

    // create elastic search client
    public static RestHighLevelClient createClient(){

        //////////////////////////
        /////////// IF YOU USE LOCAL ELASTICSEARCH
        //////////////////////////

        //  String hostname = "localhost";
        String hostname = "bos-spencer-nba-test";
        RestClientBuilder builder = RestClient.builder(new HttpHost(hostname,9200,"http"));


        //////////////////////////
        /////////// IF YOU USE BONSAI / HOSTED ELASTICSEARCH
        //////////////////////////

//        // replace with your own credentials
//        String hostname = ""; // localhost or bonsai url
//        String username = ""; // needed only for bonsai
//        String password = ""; // needed only for bonsai
//
//        // credentials provider help supply username and password
//        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
//        credentialsProvider.setCredentials(AuthScope.ANY,
//                new UsernamePasswordCredentials(username, password));
//
//        RestClientBuilder builder = RestClient.builder(
//                new HttpHost(hostname, 443, "https"))
//                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
//                    @Override
//                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
//                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
//                    }
//                });
//
        RestHighLevelClient client = new RestHighLevelClient(builder);
        return client;
    }

    public static KafkaConsumer<String, String> createConsumer(String topic) {
        String bootstrapServers = "bos-spencer-nba-test:9092";
        String groupId = "kafka-demo-elasticsearch11";
        // String topic = "twitter_tweets";

        // create consumer properties
        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");  // disable auto commit of offsets
        properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");   // make this small for now

        // create consumer
        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(properties);
        consumer.subscribe(Arrays.asList(topic));

        return consumer;

    }

    public static void createElasticSearchTweeterTweets() throws IOException {
        final Logger logger = LoggerFactory.getLogger(ConsumerElasticSearch.class.getName());

        RestHighLevelClient client = createClient();

        String jsonString = "{ \"foo\": \"bar\" }";

        // our index must already exist so create it using postman: PUT /twitter
        IndexRequest indexRequest = new IndexRequest(
                "twitter",
                "tweets"
        ).source(jsonString, XContentType.JSON);

        IndexResponse indexResponse = client.index(indexRequest, RequestOptions.DEFAULT);
        String id = indexResponse.getId();

        logger.info(id);


        // close the client gracefully
        client.close();
    }

    public static void main(String[] args) throws IOException {
//        createElasticSearchTweeterTweets();

        final Logger logger = LoggerFactory.getLogger(ConsumerElasticSearch.class.getName());

        RestHighLevelClient client = createClient();

        KafkaConsumer<String, String> consumer = createConsumer("first_topic");

        // poll for new data
        // using while is not a good practice, since we will need to manually break out of the loop when running
        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

            Integer recordCount = records.count();
            logger.info("Received " + recordCount + " records");

            BulkRequest bulkRequest = new BulkRequest();

            for (ConsumerRecord<String,String> record: records) {
//                logger.info("Key: " + record.key() + ", Value: " + record.value());
//                logger.info("Partition: " + record.partition() + ", Offset: " + record.offset());

                String key = (record.key() == null) ? "null" : record.key();
                // some of values had some non-ascii chars, put in a space instead
                String value = record.value().replaceAll("\\p{Cntrl}", " ");
                String jsonString = "{\"key\":" + "\""+key+ "\""
                        + ",\"value\":" + "\""+value+ "\""
                        + ",\"partition\":" + "\"" +record.partition()+ "\""
                        + ",\"offset\":" + "\"" +record.offset()+ "\""
                        + "}";

//                logger.info("JSON String: " + jsonString);

//                // temp test code using gson json parser
//                JsonParser jsonParser = new JsonParser();
//                JsonElement tempElement = jsonParser.parse(jsonString)
//                                .getAsJsonObject()
//                                .get("value");
//                String tempString = tempElement.getAsString();

                // to be idempotent, we will generate our own id instead of elasticsearch generating one for us.
                // this way if we reprocess the same message it will not create a new item in elasticsearch
                 String recordId = record.topic() + "_" + record.partition() + "_" + record.offset();

                // insert data into ElasticSearch
                // our index must already exist so create it using postman: PUT /twitter
                IndexRequest indexRequest = new IndexRequest(
                        "twitter",
                        "tweets",
                        recordId
                ).source(jsonString, XContentType.JSON);

                // we add to our bulk request
                bulkRequest.add(indexRequest);

                // don't need this anymore since we are using bulk request, which is more efficient
//                IndexResponse indexResponse = client.index(indexRequest, RequestOptions.DEFAULT);
//                String id = indexResponse.getId();
//                logger.info(id);
//
//                // introduce a small delay for demo purposes
//                try {
//                    Thread.sleep(10);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
            }

            if (recordCount > 0) {
                BulkResponse bulkItemResponses = client.bulk(bulkRequest, RequestOptions.DEFAULT);
                logger.info("Committing offsets...");
                consumer.commitSync();
                logger.info("Offsets have been committed");
                // introduce a small delay for demo purposes
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        }

        // close the client gracefully
        // client.close();
    }

}
