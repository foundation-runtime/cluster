package com.cisco.oss.foundation.cluster.registry;

import com.cisco.oss.foundation.cluster.masterslave.MastershipElector;
import com.cisco.oss.foundation.cluster.masterslave.consul.ConsulMastershipElector;
import com.cisco.oss.foundation.cluster.masterslave.consul.OpenstackConsulMastershipElector;
import com.cisco.oss.foundation.cluster.masterslave.mongo.AsyncMongoMastershipElector;
import com.cisco.oss.foundation.cluster.masterslave.mongo.MongoMastershipElector;
import com.cisco.oss.foundation.cluster.utils.MasterSlaveConfigurationUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Runnable to do all the logic of acquiring mastership.
 * There will be an instance per logical jobName invoked by #MasterSlaveRegistry.addMasterSlaveListener
 * The runnable will query mongo and try to acquire a lock based on the componet-jobName, the logical jobName and the timestamp
 * Created by Yair Ogen (yaogen) on 24/01/2016.
 */
public class MasterSlaveRunnable implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasterSlaveRunnable.class);
    private final String jobName;
    private MasterSlaveListener masterSlaveListener;
    private final String id;
    static final ThreadLocal<Boolean> masterNextTimeInvoke = new ThreadLocal<>();
    static final ThreadLocal<Boolean> slaveNextTimeInvoke = new ThreadLocal<>();
    private final MastershipElector mastershipElector;

    private MastershipElector createElector() {
        String mastershipElectorImpl = MasterSlaveConfigurationUtil.getMasterSlaveImpl();
        LOGGER.info("creating new instance of {} mastership elector for job: {}", mastershipElectorImpl, jobName);
        switch (mastershipElectorImpl){
            case "consul":{
                return new ConsulMastershipElector();
            }
            case "consul-openstack":{
                return new OpenstackConsulMastershipElector();
            }
            case "async-mongo":{
                return new AsyncMongoMastershipElector();
            }
            case "mongo":{
                return new MongoMastershipElector();
            }
            default:{
                try {
                    Class mastershipElectorImplClass = Class.forName(mastershipElectorImpl);
                    return (MastershipElector) mastershipElectorImplClass.newInstance();
                } catch (Exception e) {
                    LOGGER.error("can't create mastership elector. error is: {}", e);
                    throw new IllegalArgumentException("can't create mastership elector");
                }
            }
        }
    }

    public MasterSlaveRunnable(String jobName, MasterSlaveListener masterSlaveListener) {
        this.jobName = jobName;
        mastershipElector = createElector();
        this.masterSlaveListener = masterSlaveListener;
        this.id = MasterSlaveConfigurationUtil.COMPONENT_NAME + "-" + jobName;

        //cleanup so we don't keep zombie masters registered
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                mastershipElector.close();
            }
        }));
    }

    @Override
    public void run() {

        masterNextTimeInvoke.set(Boolean.TRUE);
        slaveNextTimeInvoke.set(Boolean.TRUE);

        //sleep a bit until the async load and connection to DB is finished.
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
            LOGGER.error("INTERRUPTED");
        }

        Boolean runThread = MasterSlaveRegistry.INSTANCE.threadController.getOrDefault(jobName, Boolean.TRUE);
        String currentVersion = mastershipElector.getActiveVersion();
        mastershipElector.init(id, jobName);

        while (runThread) {

            int masterSlaveLeaseTime = MasterSlaveConfigurationUtil.getMasterSlaveLeaseTime(jobName);

            try {

                chooseMaster(currentVersion);

            } catch (Exception e) {
                LOGGER.warn("problem running master slave thread for: {}. RETRYING ONCE. error is: {}", jobName, e, e);
                try {
                    chooseMaster(currentVersion);
                } catch (Exception e1) {
                    LOGGER.error("problem running master slave thread for: {}. error is: {}", jobName, e1, e1);
                    if (slaveNextTimeInvoke.get()) {
                        String message = e.getMessage();
                        goSlave("Error: " + message != null ? message : e.getClass().getSimpleName());
                    }
                }
            } finally {
                try {
                    TimeUnit.MILLISECONDS.sleep(masterSlaveLeaseTime * 1000/2);
                } catch (InterruptedException e) {
                    //ignore
                }
                runThread = MasterSlaveRegistry.INSTANCE.threadController.getOrDefault(jobName, Boolean.TRUE);
            }

        }

        //if we get here, we were stopped. we should clean-up
        mastershipElector.close();
    }

    private void chooseMaster(String currentVersion) {

        boolean isActiveDC = isActiveDC();

        if (isActiveDC && mastershipElector.isReady()) {

            //if anything fails - fallback to activeVersion is true
            boolean isActiveVersion = mastershipElector.isActiveVersion(currentVersion);

            if (isActiveVersion) {

                switch (MasterSlaveConfigurationUtil.getMasterSlaveMultiplicity(jobName)) {
                    case SINGLE: {
//                        chooseMasterBasedOnLease(masterSlaveLeaseTime, leaseRenewed, masterSlaveCollection, document);
                        if (mastershipElector.isMaster()) {
                            if (masterNextTimeInvoke.get()) {
                                goMaster();
                            }
                        } else {
                            if (slaveNextTimeInvoke.get()) {
                                goSlave("is-master logic returned false");
                            }
                        }
                        break;
                    }
                    case MULTI: {
                        if (masterNextTimeInvoke.get()) {
                            goMaster();
                        }
                        break;
                    }
                    default: {
                        if (mastershipElector.isMaster()) {
                            if (masterNextTimeInvoke.get()) {
                                goMaster();
                            }
                        } else {
                            if (slaveNextTimeInvoke.get()) {
                                goSlave("is-master logic returned false");
                            }
                        }
                    }
                }


            } else if (slaveNextTimeInvoke.get()) { //Not active version
                goSlave("Not Active Version");
            }
        } else if (!isActiveDC && slaveNextTimeInvoke.get()) { //Not active Datacenter
            goSlave("Not Active DC");
        }
    }

//    private void chooseMasterBasedOnLease(int masterSlaveLeaseTime, long leaseRenewed, MongoCollection masterSlaveCollection, Document document) {
//        long lastExpectedLeaseUpdateTime = leaseRenewed - masterSlaveLeaseTime * 1000;
//        ConditionBuilder timeQuery = QueryBuilder.where(LEASE_RENEWED).lessThanOrEqualTo(lastExpectedLeaseUpdateTime);
//        Document updateLeaseQuery = QueryBuilder.and(QueryBuilder.where(ID).equals(this.id), timeQuery);
//
//        LOGGER.trace("id: {}, leaseRenewed: {}, lease-time: {}, lastExpectedLeaseUpdateTime: {}", id, leaseRenewed, masterSlaveLeaseTime, lastExpectedLeaseUpdateTime);
////                    long numOfRowsUpdated = masterSlaveCollection.update(updateLeaseQuery, document,false, true);
//        long numOfRowsUpdated = masterSlaveCollection.update(updateLeaseQuery, document);
//
//        if (numOfRowsUpdated > 0) {
//            if (masterNextTimeInvoke.get()) {
//                goMaster();
//            }
//        } else {
//            if (slaveNextTimeInvoke.get()) {
//                goSlave();
//            }
//        }
//    }


    public void goMaster() {
        LOGGER.debug("{} is going to turn into master", MasterSlaveConfigurationUtil.INSTANCE_ID);
        masterNextTimeInvoke.set(Boolean.FALSE);
        slaveNextTimeInvoke.set(Boolean.TRUE);
        masterSlaveListener.goMaster();
        LOGGER.info("{} is now master", MasterSlaveConfigurationUtil.INSTANCE_ID);
    }

    public void goSlave(String reason) {
        LOGGER.debug("{} is going to turn into slave", MasterSlaveConfigurationUtil.INSTANCE_ID);
        slaveNextTimeInvoke.set(Boolean.FALSE);
        masterNextTimeInvoke.set(Boolean.TRUE);
        mastershipElector.cleanupMaster();
        masterSlaveListener.goSlave();
        LOGGER.info("{} is now slave. Reason: {}", MasterSlaveConfigurationUtil.INSTANCE_ID, reason);
    }

    private boolean isActiveDC() {

        //if we don't need to be single across datacetners we're in active DC for all we care.
        if (!MasterSlaveConfigurationUtil.isSingleAcrossMDC(jobName)) {
            return true;
        }

        String currentDC = MasterSlaveConfigurationUtil.ACTIVE_DATA_CENTER;
        if (StringUtils.isBlank(currentDC)) {
            return true;
        } else {
            try {
                return mastershipElector.isActiveDataCenter(currentDC);
            } catch (Exception e) {
                //if this fails  for any reason we treat this as a non DC supported environment.
                LOGGER.error("problem reading datacenter collection - assuming in active DC");
                return true;
            }
        }
    }

}
