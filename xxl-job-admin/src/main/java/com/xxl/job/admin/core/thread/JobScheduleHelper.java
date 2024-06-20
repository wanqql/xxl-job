package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.conf.XxlJobAdminConfig;
import com.xxl.job.admin.core.cron.CronExpression;
import com.xxl.job.admin.core.model.XxlJobInfo;
import com.xxl.job.admin.core.scheduler.MisfireStrategyEnum;
import com.xxl.job.admin.core.scheduler.ScheduleTypeEnum;
import com.xxl.job.admin.core.trigger.TriggerTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author xuxueli 2019-05-21
 */
public class JobScheduleHelper {
    private static Logger logger = LoggerFactory.getLogger(JobScheduleHelper.class);

    private static JobScheduleHelper instance = new JobScheduleHelper();
    public static JobScheduleHelper getInstance(){
        return instance;
    }

    public static final long PRE_READ_MS = 5000;    // pre read

    private Thread scheduleThread;
    private Thread ringThread;
    private volatile boolean scheduleThreadToStop = false;
    private volatile boolean ringThreadToStop = false;
    /**
     * 时间轮任务容器，刻度是0-59s
     * 添加是在任务调度线程里面
     * 删除是在时间轮线程里面
     */
    private volatile static Map<Integer, List<Integer>> ringData = new ConcurrentHashMap<>();

    public void start(){

        // schedule thread
        // 任务调度线程，将需要执行的任务放入时间轮容器中
        scheduleThread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis()%1000 );
                } catch (InterruptedException e) {
                    if (!scheduleThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>> init xxl-job admin scheduler success.");

                // pre-read count: treadpool-size * trigger-qps (each trigger cost 50ms, qps = 1000/50 = 20)
                int preReadCount = (XxlJobAdminConfig.getAdminConfig().getTriggerPoolFastMax() + XxlJobAdminConfig.getAdminConfig().getTriggerPoolSlowMax()) * 20;

                while (!scheduleThreadToStop) {

                    // Scan Job
                    long start = System.currentTimeMillis();

                    Connection conn = null;
                    Boolean connAutoCommit = null;
                    PreparedStatement preparedStatement = null;

                    boolean preReadSuc = true;
                    try {

                        conn = XxlJobAdminConfig.getAdminConfig().getDataSource().getConnection();
                        connAutoCommit = conn.getAutoCommit();
                        conn.setAutoCommit(false);
                        // 分布式锁
                        preparedStatement = conn.prepareStatement(  "select * from xxl_job_lock where lock_name = 'schedule_lock' for update" );
                        preparedStatement.execute();

                        // tx start

                        // 1、pre read
                        long nowTime = System.currentTimeMillis();
                        // 获取接下来5s内需要执行的定时任务
                        List<XxlJobInfo> scheduleList = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleJobQuery(nowTime + PRE_READ_MS, preReadCount);
                        if (scheduleList!=null && scheduleList.size()>0) {
                            // 2、push time-ring
                            for (XxlJobInfo jobInfo: scheduleList) {

                                // time-ring jump
                                /*
                                 * 想一想，既然每次都是读取接下来5s内需要执行的任务，但是为什么出现当前时间大于任务下次执行的时间 + 5s呢？
                                 * 其实可能的原因是系统挂了，导致需要执行的任务没有执行，比如当前0s, 系统挂了，12s后恢复，那么这个5s内需要执行的任务就没有执行
                                 */
                                if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {
                                    // 2.1、trigger-expire > 5s：pass && make next-trigger-time
                                    logger.warn(">>>>>>>>>>> xxl-job, schedule misfire, jobId = " + jobInfo.getId());

                                    // 1、misfire match
                                    /*
                                     * 这个跟配置的任务配置的过期策略有关：
                                     * - 调度过期策略：
                                     *   - 忽略：调度过期后，忽略过期的任务，从当前时间开始重新计算下次触发时间；
                                     *   - 立即执行一次：调度过期后，立即执行一次，并从当前时间开始重新计算下次触发时间
                                     */
                                    MisfireStrategyEnum misfireStrategyEnum = MisfireStrategyEnum.match(jobInfo.getMisfireStrategy(), MisfireStrategyEnum.DO_NOTHING);
                                    if (MisfireStrategyEnum.FIRE_ONCE_NOW == misfireStrategyEnum) {
                                        // FIRE_ONCE_NOW 》 trigger
                                        // 如果策略是立即执行一次，那么立即执行
                                        JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.MISFIRE, -1, null, null, null);
                                        logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );
                                    }

                                    // 2、fresh next
                                    // 计算并设置任务的下一次执行时间
                                    refreshNextValidTime(jobInfo, new Date());

                                }
                                /*
                                 * 上个if没有命中但是这个if命中，说明在这个调度线程执行的上一轮中，有任务没有被执行，可能的情况是系统在上一轮中挂了，但是很快恢复了，
                                 * 比如1s的时候，有个任务需要在2s的时候执行，但是系统在1s的时候挂了，3s的时候恢复过来了，那么需要在2s执行的任务就错过调度了
                                 */
                                else if (nowTime > jobInfo.getTriggerNextTime()) {
                                    // 2.2、trigger-expire < 5s：direct-trigger && make next-trigger-time

                                    // 1、trigger
                                    // 所以需要立马触发
                                    JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.CRON, -1, null, null, null);
                                    logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );

                                    // 2、fresh next
                                    // 刷新下一次执行时间
                                    refreshNextValidTime(jobInfo, new Date());

                                    // next-trigger-time in 5s, pre-read again
                                    /*
                                     * 如果任务下次执行时间在5s内，那么说明任务需要被调度执行
                                     */
                                    if (jobInfo.getTriggerStatus()==1 && nowTime + PRE_READ_MS > jobInfo.getTriggerNextTime()) {

                                        // 1、make ring second
                                        // 计算在时间轮上的刻度
                                        int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                        // 2、push time ring
                                        // 放入时间轮容器中
                                        pushTimeRing(ringSecond, jobInfo.getId());

                                        // 3、fresh next
                                        // 刷新并计算任务的下一次执行时间
                                        refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                    }

                                }
                                /*
                                 * 这里就说明是正常的任务，也是普遍正常的情况
                                 */
                                else {
                                    // 2.3、trigger-pre-read：time-ring trigger && make next-trigger-time

                                    // 1、make ring second
                                    // 计算在时间轮上的刻度
                                    int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                    // 2、push time ring
                                    // 放入时间轮容器中
                                    pushTimeRing(ringSecond, jobInfo.getId());

                                    // 3、fresh next
                                    // 刷新并计算任务的下一次执行时间
                                    refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                }

                            }

                            // 3、update trigger info
                            for (XxlJobInfo jobInfo: scheduleList) {
                                // 将这些任务更新到库中
                                XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleUpdate(jobInfo);
                            }

                        } else {
                            // 走到这里，说明接下来5s没有需要调度的任务，那么调度线程可以休息一下
                            preReadSuc = false;
                        }

                        // tx stop


                    } catch (Exception e) {
                        if (!scheduleThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread error:{}", e);
                        }
                    } finally {

                        // commit
                        if (conn != null) {
                            try {
                                conn.commit();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.setAutoCommit(connAutoCommit);
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }

                        // close PreparedStatement
                        if (null != preparedStatement) {
                            try {
                                preparedStatement.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }
                    }
                    long cost = System.currentTimeMillis()-start;


                    // Wait seconds, align second
                    /*
                     * 如果当前一次调度的执行时间在1s内，那么线程需要休息一会
                     * 为什么需要休息？
                     * 因为如果当前调度在100ms内执行完了，那么立马查询接下来5s内需要调度的任务没有什么必要，因为任务的执行间隔至少也是1s
                     */
                    if (cost < 1000) {  // scan-overtime, not wait
                        try {
                            // pre-read period: success > scan each second; fail > skip this period;
                            /*
                             * 如果当前查出来没有需要调度的任务，那么需要休息4-5s
                             */
                            TimeUnit.MILLISECONDS.sleep((preReadSuc?1000:PRE_READ_MS) - System.currentTimeMillis()%1000);
                        } catch (InterruptedException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }

                }

                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread stop");
            }
        });
        scheduleThread.setDaemon(true);
        scheduleThread.setName("xxl-job, admin JobScheduleHelper#scheduleThread");
        scheduleThread.start();


        // ring thread
        // 时间轮线程
        ringThread = new Thread(new Runnable() {
            @Override
            public void run() {

                while (!ringThreadToStop) {

                    // align second
                    /*
                     * 等待下一轮执行，如果当前是100ms，那么就等待900ms
                     */
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
                    } catch (InterruptedException e) {
                        if (!ringThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                    try {
                        // second data
                        // 当前刻度上的任务
                        List<Integer> ringItemData = new ArrayList<>();
                        /*
                         * 向前校验一个刻度，如果当前是3s，则取出3s和2s的任务
                         * 避免处理过长跨过刻度的问题
                         */
                        int nowSecond = Calendar.getInstance().get(Calendar.SECOND);   // 避免处理耗时太长，跨过刻度，向前校验一个刻度；
                        for (int i = 0; i < 2; i++) {
                            List<Integer> tmpData = ringData.remove( (nowSecond+60-i)%60 );
                            if (tmpData != null) {
                                ringItemData.addAll(tmpData);
                            }
                        }

                        // ring trigger
                        // 将取出刻度上的任务进行交给触发器去执行
                        logger.debug(">>>>>>>>>>> xxl-job, time-ring beat : " + nowSecond + " = " + Arrays.asList(ringItemData) );
                        if (ringItemData.size() > 0) {
                            // do trigger
                            for (int jobId: ringItemData) {
                                // do trigger
                                JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
                            }
                            // clear
                            ringItemData.clear();
                        }
                    } catch (Exception e) {
                        if (!ringThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread error:{}", e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread stop");
            }
        });
        ringThread.setDaemon(true);
        ringThread.setName("xxl-job, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }

    private void refreshNextValidTime(XxlJobInfo jobInfo, Date fromTime) throws Exception {
        Date nextValidTime = generateNextValidTime(jobInfo, fromTime);
        if (nextValidTime != null) {
            jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
            jobInfo.setTriggerNextTime(nextValidTime.getTime());
        } else {
            jobInfo.setTriggerStatus(0);
            jobInfo.setTriggerLastTime(0);
            jobInfo.setTriggerNextTime(0);
            logger.warn(">>>>>>>>>>> xxl-job, refreshNextValidTime fail for job: jobId={}, scheduleType={}, scheduleConf={}",
                    jobInfo.getId(), jobInfo.getScheduleType(), jobInfo.getScheduleConf());
        }
    }

    private void pushTimeRing(int ringSecond, int jobId){
        // push async ring
        List<Integer> ringItemData = ringData.get(ringSecond);
        if (ringItemData == null) {
            ringItemData = new ArrayList<Integer>();
            ringData.put(ringSecond, ringItemData);
        }
        ringItemData.add(jobId);

        logger.debug(">>>>>>>>>>> xxl-job, schedule push time-ring : " + ringSecond + " = " + Arrays.asList(ringItemData) );
    }

    public void toStop(){

        // 1、stop schedule
        scheduleThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);  // wait
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (scheduleThread.getState() != Thread.State.TERMINATED){
            // interrupt and wait
            scheduleThread.interrupt();
            try {
                scheduleThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // if has ring data
        boolean hasRingData = false;
        if (!ringData.isEmpty()) {
            for (int second : ringData.keySet()) {
                List<Integer> tmpData = ringData.get(second);
                if (tmpData!=null && tmpData.size()>0) {
                    hasRingData = true;
                    break;
                }
            }
        }
        if (hasRingData) {
            try {
                TimeUnit.SECONDS.sleep(8);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // stop ring (wait job-in-memory stop)
        ringThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (ringThread.getState() != Thread.State.TERMINATED){
            // interrupt and wait
            ringThread.interrupt();
            try {
                ringThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper stop");
    }


    // ---------------------- tools ----------------------
    public static Date generateNextValidTime(XxlJobInfo jobInfo, Date fromTime) throws Exception {
        ScheduleTypeEnum scheduleTypeEnum = ScheduleTypeEnum.match(jobInfo.getScheduleType(), null);
        if (ScheduleTypeEnum.CRON == scheduleTypeEnum) {
            Date nextValidTime = new CronExpression(jobInfo.getScheduleConf()).getNextValidTimeAfter(fromTime);
            return nextValidTime;
        } else if (ScheduleTypeEnum.FIX_RATE == scheduleTypeEnum /*|| ScheduleTypeEnum.FIX_DELAY == scheduleTypeEnum*/) {
            return new Date(fromTime.getTime() + Integer.valueOf(jobInfo.getScheduleConf())*1000 );
        }
        return null;
    }

}
