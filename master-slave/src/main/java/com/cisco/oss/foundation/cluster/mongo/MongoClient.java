package com.cisco.oss.foundation.cluster.mongo;

import com.cisco.oss.foundation.cluster.utils.ConfigurationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.allanbank.mongodb.Credential.Builder;
import com.allanbank.mongodb.MongoClientConfiguration;
import com.allanbank.mongodb.MongoCollection;
import com.allanbank.mongodb.MongoDatabase;
import com.allanbank.mongodb.MongoFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Created by igreenfi on 9/21/2015.
 */
public enum MongoClient {

	INSTANCE;

	private Logger logger = null;
	private static final String DATA_CENTER_COLLECTION = "dataCenter";

	private static final String MASTER_SLAVE_COLLECTION = "masterSlave";

	MongoDatabase database;
	MongoCollection dataCenter;

    MongoCollection masterSlave;

    MongoClient() {

		logger = LoggerFactory.getLogger(MongoClient.class);
    	
    	database = connect();

		dataCenter	= database.getCollection(DATA_CENTER_COLLECTION);
		masterSlave		= database.getCollection(MASTER_SLAVE_COLLECTION);
	}

	private MongoDatabase connect(){
		MongoClientConfiguration config = new MongoClientConfiguration();
    	config.addServer(ConfigurationUtil.getMongodbHost() + ":" + ConfigurationUtil.getMongodbPort());
    	config.setMaxConnectionCount(10);

    	String dbName = ConfigurationUtil.getMongodbName();
    	Boolean isAuthenticationEnabled = ConfigurationUtil.getIsMongodbAuthenticationEnabled();
    	
		if (isAuthenticationEnabled) {
			final boolean encryptionEnabled = ConfigurationUtil.getIsMongodbEncryptedPassword().equals("true");

			final String userName = ConfigurationUtil.getMongodbUserName();

			if(encryptionEnabled){
				throw new UnsupportedOperationException("pwd encryption not supported");
			}

			final String password = ConfigurationUtil.getMongodbPassword() ;


			Builder credentials = new Builder();
			credentials.userName(userName);
			credentials.password(password.toCharArray());
			credentials.setDatabase(dbName);
			
			config.addCredential(credentials);
		}

    	
    	com.allanbank.mongodb.MongoClient mongoClient = MongoFactory.createClient(config);
    	
    	database = mongoClient.getDatabase(dbName);
    	
		//Check Authentication
		try {
			database.getCollection(DATA_CENTER_COLLECTION).count();
		} catch (Exception e) { //will raise an error if authentication fails or if server is down
			String message = "Can't connect to '" + dbName + "' mongoDB. Please check connection and configuration. MongoDB error message: " + e.toString();
			logger.error(message, e);
			throw new RuntimeException(message);
		}
    	
		return database;
	}

    public MongoCollection getDataCenterCollection() {
        return dataCenter;
    }
    
    public MongoCollection getMasterSlaveCollection() {
        return masterSlave;
    }
}