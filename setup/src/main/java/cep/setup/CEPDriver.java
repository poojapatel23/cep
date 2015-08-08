package cep.setup;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import cep.handler.MyHandler;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBStreamsClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeStreamRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeStreamResult;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.GetRecordsRequest;
import com.amazonaws.services.dynamodbv2.model.GetRecordsResult;
import com.amazonaws.services.dynamodbv2.model.GetShardIteratorRequest;
import com.amazonaws.services.dynamodbv2.model.GetShardIteratorResult;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.Record;
import com.amazonaws.services.dynamodbv2.model.Shard;
import com.amazonaws.services.dynamodbv2.model.ShardIteratorType;
import com.amazonaws.services.dynamodbv2.model.StreamSpecification;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
//import com.amazonaws.services.dynamodbv2.model.StreamSpecification;
import com.amazonaws.services.dynamodbv2.model.StreamViewType;

public class CEPDriver {
	static DynamoDB dynamoDB = new DynamoDB(new AmazonDynamoDBClient(
            new ProfileCredentialsProvider()));
	
	static AmazonDynamoDBClient dynamoDBClient = 
	        new AmazonDynamoDBClient(new ProfileCredentialsProvider());
	
	static AmazonDynamoDBStreamsClient streamsClient = 
	        new AmazonDynamoDBStreamsClient(new ProfileCredentialsProvider());

	static SimpleDateFormat dateFormatter = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if ((args.length==0) || (args.length>1)) {
			System.out.println("Please supply one argument");
		}
		else if (args[0].equalsIgnoreCase("init")) {
			createTable(Tables.EVENT_STORE, 10L, 10L, "EventId", "S", null, null);
		}
		else if (args[0].equalsIgnoreCase("generate")) {
			generateEvent();
		}
		else if (args[0].equalsIgnoreCase("cleanup")) {
			deleteTable(Tables.EVENT_STORE);
		}
		else if (args[0].equalsIgnoreCase("stream")) {
			testStream();
		}
		else {
			System.out.println("Please supply a valid argument");
		}
	}

    private static void createTable(
    	String tableName, long readCapacityUnits, long writeCapacityUnits, 
        String hashKeyName, String hashKeyType, 
        String rangeKeyName, String rangeKeyType) {

        try {

            ArrayList<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
            keySchema.add(new KeySchemaElement()
                .withAttributeName(hashKeyName)
                .withKeyType(KeyType.HASH));
            
            ArrayList<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
            attributeDefinitions.add(new AttributeDefinition()
                .withAttributeName(hashKeyName)
                .withAttributeType(hashKeyType));

            if (rangeKeyName != null) {
                keySchema.add(new KeySchemaElement()
                    .withAttributeName(rangeKeyName)
                    .withKeyType(KeyType.RANGE));
                attributeDefinitions.add(new AttributeDefinition()
                    .withAttributeName(rangeKeyName)
                    .withAttributeType(rangeKeyType));
            }
            
            StreamSpecification streamSpecification = new StreamSpecification();
            streamSpecification.setStreamEnabled(true);
            streamSpecification.setStreamViewType(StreamViewType.NEW_IMAGE);

            CreateTableRequest request = new CreateTableRequest()
                    .withTableName(tableName)
                    .withKeySchema(keySchema)
                    .withProvisionedThroughput( new ProvisionedThroughput()
                    .withReadCapacityUnits(readCapacityUnits)
                    .withWriteCapacityUnits(writeCapacityUnits))
                    .withStreamSpecification(streamSpecification);

            request.setAttributeDefinitions(attributeDefinitions);

            System.out.println("Issuing CreateTable request for " + tableName);
            Table table = dynamoDB.createTable(request);
            System.out.println("Waiting for " + tableName
                + " to be created...this may take a while...");
            table.waitForActive();

        } catch (Exception e) {
            System.err.println("CreateTable request failed for " + tableName);
            System.err.println(e.getMessage());
        }
    }	
    
    private static void deleteTable(String tableName) {
        Table table = dynamoDB.getTable(tableName);
        try {
            System.out.println("Issuing DeleteTable request for " + tableName);
            table.delete();
            System.out.println("Waiting for " + tableName
                + " to be deleted...this may take a while...");
            table.waitForDelete();

        } catch (Exception e) {
            System.err.println("DeleteTable request failed for " + tableName);
            System.err.println(e.getMessage());
        }
    }    
    
	private static void generateEvent() {
		Table table = dynamoDB.getTable(Tables.EVENT_STORE);
		
		try {
			Item item = new Item()
            .withPrimaryKey("EventId", new Long(System.currentTimeMillis()).toString())
            .withString("EventType", "Software Installation")
            //device id            
            .withString("cpe", "cpe:/" + new Long(System.currentTimeMillis()).toString())
            //binary hash
            .withString("InstallationDate", dateFormatter.format(System.currentTimeMillis()));
        
			table.putItem(item);
		}
		catch (Exception ex) {
			System.err.println("Generate event failed.");
			System.err.println(ex.getMessage());
		}
		
		System.out.println("Generate Event Succeeded");
	}
	
	private static void testStream() {
		DescribeTableResult describeTableResult = dynamoDBClient.describeTable(Tables.EVENT_STORE);		
        String myStreamArn = describeTableResult.getTable().getLatestStreamArn();                
		DescribeStreamResult describeStreamResult = streamsClient.describeStream(new DescribeStreamRequest()
		            .withStreamArn(myStreamArn));
	    //String streamArn = describeStreamResult.getStreamDescription().getStreamArn();
	    List<Shard> shards = describeStreamResult.getStreamDescription().getShards();
				    		
		for (Shard shard : shards) {
		    String shardId = shard.getShardId();
		    System.out.println("Processing " + shardId + " from stream "+ myStreamArn);
					
			GetShardIteratorRequest getShardIteratorRequest = new GetShardIteratorRequest()
			    .withStreamArn(myStreamArn)
			    .withShardId(shardId)
			    .withShardIteratorType(ShardIteratorType.TRIM_HORIZON);
			GetShardIteratorResult getShardIteratorResult = 
			    streamsClient.getShardIterator(getShardIteratorRequest);
			String nextItr = getShardIteratorResult.getShardIterator();
		
			while (nextItr != null) {
			    GetRecordsResult getRecordsResult = 
			        streamsClient.getRecords(new GetRecordsRequest().
			            withShardIterator(nextItr));
			    List<Record> records = getRecordsResult.getRecords();
			    System.out.println("Getting records...");
			    for (Record record : records) {
			        System.out.println(record);		        
			    }
			    nextItr = getRecordsResult.getNextShardIterator();
			}
		}
	}	
}