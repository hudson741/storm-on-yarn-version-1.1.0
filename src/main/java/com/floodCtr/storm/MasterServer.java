package com.floodCtr.storm;

import java.io.IOException;

import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.thrift.TException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.floodCtr.YarnClient;
import com.floodCtr.job.FloodJob;
import com.floodCtr.publish.FloodContrJobPubProxy;
import com.floodCtr.rpc.FloodContrMaster;

import com.google.common.collect.Lists;

/**
 * @Description
 * @author: zhangchi
 * @Date: 2017/6/22
 */
public class MasterServer {
    private static final Logger logger = LoggerFactory.getLogger(StormThriftServiceImpl.class);

    public static void addStormComponent(FloodContrJobPubProxy floodContrJobPubProxy, String imageName, String node,String businessTag,
                                         String containerName, String dockerIp, String dockerArgs,
                                         Map<String, String> port, List<String> zkList,
                                         FloodJob.CM cm,
                                         String appId,
                                         List<String> nimbusSeedsList,FloodJob.PRIORITY priority)
            throws TException {
        String   jobId    = UUID.randomUUID().toString();

        FloodJob floodJob = new FloodJob(jobId, cm);

        String   netUrl   = System.getenv("netUrl");

        // 调用服务
        String zkHostsArrays = "[";

        for (Object zkHost : zkList) {
            zkHostsArrays=zkHostsArrays+"\\\""+zkHost+"\\\" ";
        }

        zkHostsArrays = zkHostsArrays.substring(0, zkHostsArrays.length() - 1)+"]";

        dockerArgs = dockerArgs+" -c storm.zookeeper.servers="+zkHostsArrays;

        String hadoopUser = System.getenv("hadoopUser");
        String nimbusSeedsArray = "[";
        for (Object nimbus : nimbusSeedsList) {
            nimbusSeedsArray=nimbusSeedsArray+"\\\""+nimbus+"\\\""+",";
        }

        nimbusSeedsArray = nimbusSeedsArray.substring(0, nimbusSeedsArray.length() - 1)+"]";
        dockerArgs       = dockerArgs + " -c nimbus.seeds=" + nimbusSeedsArray;
        floodJob.netUrl(netUrl);
        if(StringUtils.isNotEmpty(node)){
            floodJob.nodeBind(node);
        }

        logger.info("fuck dockerargs "+dockerArgs);
        floodJob.businessTag(businessTag)
                .priority(priority);

        String localDir = StringUtils.isEmpty(appId)?
                "/home/"+hadoopUser+"/stormlog":
                "/home/"+hadoopUser+"/stormlog/"+appId;

        floodJob.buildDockerCMD()
                .imageName(imageName)
                .containerName(containerName)
                .hostName(containerName)
                .host(containerName, dockerIp)
                .ip(dockerIp)
                .ports(port)
                .volume(localDir,"/opt/storm/logs")
                .volume("/etc/localtime","/etc/localtime")
                .dockerArgs(dockerArgs);
        floodContrJobPubProxy.publishJob(floodJob, node, priority);
    }


    public static void main(String[] args) throws IOException, YarnException {

        /**
         * 个性化thrift接口实现，每个使用者自定义实现
         */
        final FloodContrJobPubProxy floodContrJobPubProxy = new FloodContrJobPubProxy(YarnClient.getInstance());
        final FloodContrMaster      floodContrMaster      = new FloodContrMaster(
                                                                    new StormThriftService.Processor<>(
                                                                    new StormThriftServiceImpl(YarnClient.getInstance(),floodContrJobPubProxy)),
                                                                    9050) {
            @Override
            public void initExecute() {
                String       dockerUiIp = System.getenv("uiIp");
                String       zk         = System.getenv("zk");
                String       nimbusUIDockerImage = System.getenv("nimbusUIDockerImage");
                String appId = System.getenv("appId");
                List<String> zkList     = Lists.newArrayList();
                String[]     zkArray    = zk.split(",");

                for (String zkHost : zkArray) {
                    zkList.add(zkHost);
                }

                String       nimbusSeeds     = System.getenv("nimbusSeeds");
                List<String> nimbusSeedsList = Lists.newArrayList();
                String[]     nimbusArray     = nimbusSeeds.split(",");

                for (String nimbusHost : nimbusArray) {
                    nimbusSeedsList.add(nimbusHost);
                }

                List<String> nodeList = floodContrJobPubProxy.yarnClient.getNodes();
                Object[]     nodes    = nodeList.toArray();

                for (Object host : nodes) {
                    logger.info("fuck host " + host + "");
                }

                for (int i=0;i<nimbusSeedsList.size();i++) {
                    String nimbusDockerArgs = "storm  nimbus  -c nimbus.thrift.port=9005";

                    try {
                        Map<String, String> port = new HashMap<>();

                        port.put("9005", "9005");
                        MasterServer.addStormComponent(floodContrJobPubProxy,
                                nimbusUIDockerImage,
                                                       nodes[i] + "",
                                                       "nimbus",
                                                       "nimbus-"+System.currentTimeMillis(),
                                                       nimbusSeedsList.get(i),
                                                       nimbusDockerArgs,
                                                       port,
                                                       zkList,
                                                       FloodJob.CM.CMLOW,
                                                       appId,
                                                       nimbusSeedsList,FloodJob.PRIORITY.HIGH);
                    } catch (TException e) {
                        logger.error("error ", e);
                    }
                }
                String              uiDockerArgs = "storm ui -c ui.port=9092 -c nimbus.thrift.port=9005";
                Map<String, String> port       = new HashMap<>();

                port.put("9092", "9092");

                try {
                    MasterServer.addStormComponent(floodContrJobPubProxy,
                            nimbusUIDockerImage,
                                                   null,
                                                   "ui",
                                                   "ui-"+System.currentTimeMillis(),
                                                   dockerUiIp,
                                                   uiDockerArgs,
                                                   port,
                                                   zkList,
                                                   FloodJob.CM.CMLOW,
                                                   appId,
                                                   nimbusSeedsList,FloodJob.PRIORITY.LOW);
                } catch (TException e) {
                    logger.error("error ", e);
                }
            }
        };

        floodContrMaster.start();
    }
}
