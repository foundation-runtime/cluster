package com.cisco.oss.foundation.cluster.mongo;


import com.allanbank.mongodb.Credential;
import com.cisco.oss.foundation.cluster.utils.MasterSlaveConfigurationUtil;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton wrapper over the Mongo Client Driver
 * Created by Yair Ogen (yaogen) on 14/01/2016.
 */
public enum MongoClient {

	INSTANCE;

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoClient.class);

	private Logger logger = null;
	private static final String DATA_CENTER_COLLECTION = "dataCenter";
	private static final int DB_RETRY_DELAY = 10;
	private static final String MASTER_SLAVE_COLLECTION = "masterSlave";
	public AtomicBoolean IS_DB_UP = new AtomicBoolean(false);

	private MongoDatabase database;
	private MongoCollection dataCenter;

    private MongoCollection masterSlave;




    MongoClient() {

		logger = LoggerFactory.getLogger(MongoClient.class);

		if (MasterSlaveConfigurationUtil.isMongoAutoStart()) {
			try {
                database = connect();
            } catch (MissingMongoConfigException e) {
                throw e;
            } catch (Exception e) {
                infiniteConnect();
            }

			dataCenter	= database.getCollection(DATA_CENTER_COLLECTION);
			masterSlave		= database.getCollection(MASTER_SLAVE_COLLECTION);
		}
	}


	private MongoDatabase connect(){
		List<Pair<String, Integer>> mongodbServers = MasterSlaveConfigurationUtil.getMongodbServers();
		List<ServerAddress> addresses = new ArrayList<>(mongodbServers.size());
		for (Pair<String, Integer> mongodbServer : mongodbServers) {
			addresses.add(new ServerAddress(mongodbServer.getLeft(), mongodbServer.getRight()));
		}


		MongoClientOptions options = MongoClientOptions.builder().connectionsPerHost(10)
//				.threadsAllowedToBlockForConnectionMultiplier(threadsAllowedToBlockForConnectionMultiplier)
//				.readPreference(readPreference)
				.build();

		com.mongodb.MongoClient mongoDBClient = null;
		String dbName = MasterSlaveConfigurationUtil.getMongodbName();

		if (MasterSlaveConfigurationUtil.isMongoAuthenticationEnabled()) {

			Pair<String, String> mongoUserCredentials = MasterSlaveConfigurationUtil.getMongoUserCredentials();
			MongoCredential credential = MongoCredential.createCredential(mongoUserCredentials.getLeft(), dbName, mongoUserCredentials.getRight().toCharArray());
			mongoDBClient = new com.mongodb.MongoClient(addresses, Arrays.asList(credential), options);
		}else{
			mongoDBClient = new com.mongodb.MongoClient(addresses, options);
		}




    	
    	database = mongoDBClient.getDatabase(dbName);
    	
		//Check Authentication
		try {
			database.getCollection(DATA_CENTER_COLLECTION).count();
			IS_DB_UP.set(true);
		} catch (Exception e) { //will raise an error if authentication fails or if server is down
			String message = "Can't connect to '" + dbName + "' mongoDB. Please check connection and configuration. MongoDB error message: " + e.toString();
//			logger.error(message, e);
			throw new RuntimeException(message);
		}
    	
		return database;
	}

	private void infiniteConnect() {
		Thread reConnectThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!IS_DB_UP.get()) {
					try {
						connect();
						IS_DB_UP.set(true);
						logger.info("dc reconnect is successful");
					} catch (Exception e) {
						logger.warn("db reconnect failed. retrying in {} seconds. error: {}", DB_RETRY_DELAY, e);
						try {
							TimeUnit.SECONDS.sleep(DB_RETRY_DELAY);
						} catch (InterruptedException e1) {
							//ignore
						}
					}
				}
			}
		}, "Infinite-Reconnect");
		reConnectThread.start();
		reConnectThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread t, Throwable e) {
				LOGGER.error("Uncaught Exception in thread: {}. Exception is: {}", t.getName(), e);
			}
		});
	}

	public void connect(com.mongodb.MongoClient mongoDBClient){
		String dbName = MasterSlaveConfigurationUtil.getMongodbName();
		database = mongoDBClient.getDatabase(dbName);
		dataCenter	= database.getCollection(DATA_CENTER_COLLECTION);
		masterSlave		= database.getCollection(MASTER_SLAVE_COLLECTION);
	}

	/**
	 * @return the data center mongo collection
     */
	public MongoCollection getDataCenterCollection() {
        return dataCenter;
    }

    /**
	 * @return the master slave collection
     */
    public MongoCollection getMasterSlaveCollection() {
        return masterSlave;
    }
}
