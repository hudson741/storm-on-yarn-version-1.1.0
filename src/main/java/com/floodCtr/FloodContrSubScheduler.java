package com.floodCtr;

import java.io.IOException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.floodCtr.Resources.FloodContrContainerPool;
import com.floodCtr.job.FloodJob;
import com.floodCtr.job.JobRegisterPubTable;
import com.floodCtr.monitor.FloodContrRunningMonitor;
import com.floodCtr.monitor.FloodJobRunningState;

/**
 * @Description
 * @author: zhangchi
 * @Date: 2017/6/22
 */
public class FloodContrSubScheduler {
    private static final Logger LOG               = LoggerFactory.getLogger(FloodContrSubScheduler.class);
    private static final String YARN_EXECUTE_FILE = "execute.sh";
    YarnClient                  yarnClient;

    public FloodContrSubScheduler(YarnClient yarnClient) {
        this.yarnClient = yarnClient;
    }

    /**
     * 轮询查看是否有可提交的任务，并提交
     *
     * @param period
     * @param timeUnit
     */
    public void scheduleSubJob(int period, TimeUnit timeUnit) {
        Thread thread = new Thread() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.currentThread().sleep(1000);
                        LOG.info("floodinfo scheduleSubJob begin ...");

                        Container      container = FloodContrContainerPool.containerPool.take();
                        List<FloodJob> floodJobs = JobRegisterPubTable.getAllFloodContrJob();

                        LOG.info("flood size is " + floodJobs.size());

                        /**
                         * 当前无任务，释放已申请到的资源
                         */
                        if (CollectionUtils.isEmpty(floodJobs)) {
                            LOG.info("floodinfo job is null so relese the container ...");
                            yarnClient.releaseContainer(container.getId());
                        }

                        LOG.info("flood size is " + floodJobs.size());

                        for (FloodJob floodJob : floodJobs) {
                            LOG.info("floodJob is " + floodJob.getJobId());

                            FloodJobRunningState floodJobRunningState =  FloodContrRunningMonitor.floodJobRunningStates.get(floodJob.getJobId());
                            if(floodJobRunningState == null ){
                                LOG.info("already removed 不需要启动了");
                                continue;
                            }

                            //ip绑定 和 优先级必须同时匹配 才给予发布
                            if (StringUtils.isNotEmpty(floodJob.getNodeBind())) {
                                String node = floodJob.getNodeBind();

                                LOG.info("bind ip " + node + "  " + container.getNodeId().getHost()+" priority "+container.getPriority().getPriority());

                                if ((!container.getNodeId().getHost().equals(node))
                                        || (container.getPriority().getPriority() != floodJob.getPriority().getCode())) {
                                    LOG.info("bind ip " + node + "  " + container.getNodeId().getHost() +  "priority "+floodJob.getPriority()+"  "+container.getPriority().getPriority()  +" pass");

                                    continue;
                                }
                            }

                            int reqCpu    = floodJob.getCpu();
                            int reqMemory = floodJob.getMemory();

                            LOG.info("come with container mem:" + container.getResource().getMemory() + " cpu:"
                                     + container.getResource().getVirtualCores() + " job mem:" + floodJob.getMemory()
                                     + " cpu:" + floodJob.getCpu());

                            //资源需适配
                            if ((container.getResource().getMemory() == reqMemory)
                                    && (container.getResource().getVirtualCores() == reqCpu)) {
                                LOG.info("floodinfo start to submit job " + floodJob.getJobId());

                                int result = subFloodCtrJobToYarn(floodJob, container);

                                if (result > 0) {
                                    LOG.info("floodinfo job is submit sucees with id " + floodJob.getJobId());
                                    JobRegisterPubTable.removeJob(floodJob.getJobId());

                                    floodJobRunningState.setContainerId(container.getId());
                                    floodJobRunningState.setNodeId(container.getNodeId());
                                    floodJobRunningState.setRunIp(container.getNodeId().getHost());
                                    floodJobRunningState.setRunningState(FloodJobRunningState.RUNNING_STATE.RUNNING);
                                    break;
                                } else {
                                    LOG.warn("任务提交失败。。。。" + floodJob.toString());
                                    yarnClient.releaseContainer(container.getId());
                                    floodJobRunningState.setRunningState(FloodJobRunningState.RUNNING_STATE.FAILD);
                                }
                            } else {
                                LOG.info("pass with container mem:" + container.getResource().getMemory() + " cpu:"
                                         + container.getResource().getVirtualCores() + " job mem:"
                                         + floodJob.getMemory() + " cpu:" + floodJob.getCpu());
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("error ", e);
                    }
                }
            }
        };

        thread.start();
    }

    /**
     * 提交Floodjob至yarn
     *
     * @param floodContrJob
     * @param container
     */
    private int subFloodCtrJobToYarn(FloodJob floodContrJob, Container container) {
        LOG.info("submit a job " + floodContrJob.getJobId());

        FileSystem fs = null;

        try {
            fs = FileSystem.get(new YarnConfiguration());

            List<String>               yarnShell      = FloodContrTrans.floodJobToYarnCmd(floodContrJob);
            Path                       exePath        = writeYarnShell2HDFS(fs, yarnShell, container.getId() + "");
            Map<String, LocalResource> localResources = floodContrJob.getLocalResources();

            if (localResources == null) {
                localResources = new HashMap<>();
            }

            localResources.put(YARN_EXECUTE_FILE,
                               Util.newYarnAppResource(fs,
                                                       exePath,
                                                       LocalResourceType.FILE,
                                                       LocalResourceVisibility.PUBLIC));
            floodContrJob.localResources(localResources);
            LOG.info("gona start docker  with" + container.getId() + "  " + container.getNodeId());

            StringBuilder yarnCommands = new StringBuilder();

            yarnCommands.append("sh ").append(YARN_EXECUTE_FILE).append(" 1>/opt/stdout ").append("2>/opt/stderr");

            return yarnClient.startDockerContainer(container,
                                                   floodContrJob.getLocalResources(),
                                                   yarnCommands.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return 0;
    }

    private Path writeYarnShell2HDFS(FileSystem fs, List<String> yarnShell, String childDir) throws IOException {
        Path exePath = new Path(fs.getHomeDirectory(),
                                "dockershell" + Path.SEPARATOR + childDir + Path.SEPARATOR + YARN_EXECUTE_FILE);
        Path dirDst = exePath.getParent();

        fs.mkdirs(dirDst);

        FSDataOutputStream out = fs.create(exePath);

        for (String line : yarnShell) {
            out.write(line.getBytes("UTF-8"));
            out.write("\n".getBytes(), 0, "\n".length());
        }

        out.flush();
        out.close();

        return exePath;
    }
}
