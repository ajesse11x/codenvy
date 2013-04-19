/*
 *    Copyright (C) 2013 Codenvy.
 *
 */
package com.codenvy.analytics.server;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.JobDetailImpl;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * @author <a href="mailto:abazko@codenvy.com">Anatoliy Bazko</a>
 */
@SuppressWarnings("serial")
public class PreprocessingServiceImpl implements ServletContextListener {

    /** Logger. */
    private static final Logger LOGGER          = LoggerFactory.getLogger(PreprocessingServiceImpl.class);
    private static final String CRON_EXPRESSION = "0 0 1 ? * *";
    public static Scheduler     scheduler;


    /**
     * {@inheritDoc}
     */
    public void contextDestroyed(ServletContextEvent arg0) {
    }

    /**
     * {@inheritDoc}
     */
    public void contextInitialized(ServletContextEvent arg0) {
        init();
    }

    /**
     * Initialize service and precalculate data.
     */
    public void init() {
        try {
            scheduler = new StdSchedulerFactory().getScheduler();

            Trigger trigger = initTrigger();
            JobDetail jobDetail = initJobDetail();

            scheduler.scheduleJob(jobDetail, trigger);
            scheduler.start();
        } catch (Exception e) {
            LOGGER.error("Scheduler was not initialized", e);
        }
    }

    private Trigger initTrigger() {
        return TriggerBuilder
                             .newTrigger()
                             .withSchedule(
                                           CronScheduleBuilder.cronSchedule(CRON_EXPRESSION))
                             .build();
    }
    
    private JobDetail initJobDetail() {
        JobDetailImpl jobDetail = new JobDetailImpl();
        jobDetail.setKey(new JobKey("preprocess"));
        jobDetail.setJobClass(PreprocessingServiceImpl.PreprocessJob.class);
        
        return jobDetail;
    }

    public static class PreprocessJob implements Job {
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                LOGGER.info("Preprocessing data is started");

                new TimeLineViewServiceImpl().getViews(new Date());

                LOGGER.info("Preprocessing data is finished");
            } catch (IOException e) {
                throw new JobExecutionException(e);
            }
        }
    }
}
