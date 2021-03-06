/*************************************************************************
 * Copyright 2009-2014 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/

package com.eucalyptus.stats;

import com.eucalyptus.stats.configuration.StatsConfiguration;
import com.eucalyptus.scripting.Groovyness;
import com.eucalyptus.system.Threads;
import com.eucalyptus.util.Exceptions;
import com.google.common.collect.Lists;
import org.apache.log4j.Logger;

import javax.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the set of sensors registered and their execution schedules
 * Handles loading the list, updating schedules, and contains the thread pool that executes sensor updates
 */
@Singleton
public class SensorManagerImpl implements SensorManager {
    private static final Logger LOG = Logger.getLogger(SensorManagerImpl.class);
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(
        StatsConfiguration.getMonitoringThreadPoolSize( ),
        Threads.threadFactory( "ts-sensor-monitoring-pool-%d" ) );
    private final List<SensorEntry> sensorList = new ArrayList<>(50);
    private final static long initialDelaySeconds = 0l; //Wait 10 sec before starting monitoring sensors
    private EventEmitterService emitterService;


    @Override
    public void check() {
        if( executorService == null || sensorList == null || emitterService == null) {
            throw Exceptions.toUndeclared("SensorManager not ready");
        }
    }

    @Override
    public void pollAll() {
        //Force run each. Could take a while
        for (SensorEntry e : sensorList) {
            buildMetricQueryRunnable(e).run();
        }
    }

    @Override
    public List<SystemMetric> getMetrics() {
        List<SystemMetric> metrics = Lists.newArrayList();
        for (SensorEntry e : sensorList) {
            try {
                metrics.addAll(e.getSensor().poll());
            } catch (Throwable f) {
                LOG.warn("Error polling sensor: " + e.getSensor().getName(), f);
            }
        }
        return metrics;
    }

    /**
     * linux fs filechange notice
     * Loads the sensor listing from a script with path:
     * MonitoringConfiguration.sensorCodeDirectory/MonitoringConfiguration.sensorConfigGroovyScript
     * Example: /etc/eucalyptus/cloud.d/scripts/stats_sensorsrs.groovy
     *
     * @return
     */
    private static List<SensorEntry> getSensorList() {
        LOG.info("Reloading sensor list from: " + StatsConfiguration.sensorCodeDirectory.toString() + "/" + StatsConfiguration.getSensorConfigScript());
        try {
            return Groovyness.run(StatsConfiguration.sensorCodeDirectory, StatsConfiguration.getSensorConfigScript());
        } catch (Exception e) {
            LOG.error("Could not load sensor list groovy script", e);
            return null;
        }
    }

    @Override
    public void start() {
        executorService.execute(CONFIG_CHECK_TASK);
        for (SensorEntry sensor : sensorList) {
            LOG.info("Adding sensor to schedule: " + sensor.getSensor().getName() + " Interval = " + sensor.getQueryInterval());
            executorService.scheduleAtFixedRate(buildMetricQueryRunnable(sensor), initialDelaySeconds, sensor.getQueryInterval(), TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        this.executorService.shutdownNow();
    }

    @Override
    public synchronized void init(EventEmitterService eventEmitter) {
        List<SensorEntry> sensors = getSensorList();
        if (sensors == null) {
            throw new RuntimeException("Error reloading sensor list. No sensor changes made.");
        }
        this.sensorList.clear();
        this.emitterService = eventEmitter;
        this.sensorList.addAll(sensors);
    }

    @Override
    public EventEmitterService getEventEmitterService() {
        return this.emitterService;
    }

    private Runnable buildMetricQueryRunnable(final SensorEntry sensor) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    List<SystemMetric> result = sensor.getSensor().poll();
                    try {
                        //Submit to emitter service.
                        for (SystemMetric m : result) {
                            emitterService.offer(m);
                        }
                    } catch (Exception e) {
                        LOG.warn("Sensor ran, but emitting event failed.", e);
                    }
                } catch (Exception e) {
                    LOG.warn("Sensor failed to execute: " + sensor, e);
                }
            }
        };

    }

    //Reload the set of sensors to run. Re-read the config
    public synchronized void reload() {
        this.stop();
        init(this.emitterService);
    }

    final Runnable CONFIG_CHECK_TASK = new Runnable() {
        FileTime lastModTime;

        @Override
        public void run() {
            if(configChanged()) {
                int failCount = 3;
                long retryBackoffMs = 2 * 1000l;

                //Force a reload of the manager to pickup the new config
                for(int i = 0; i < failCount; i++) {
                    try {
                        LOG.info("Initiating stats manager reload due to configuration change. Attempt " + (i + 1) + " of " + failCount);
                        StatsManager.stop();
                        StatsManager.start();
                        LOG.info("Completed stats manager reload due triggered from configuration change");
                        return;
                    } catch (Exception e) {
                        LOG.error("Error reloading the stats manager on configuration update. Waiting " + retryBackoffMs + "ms before next retry");
                        if(i >= failCount - 1) {
                            //Don't sleep on failure path
                            break;
                        }
                        try {
                            Thread.sleep(retryBackoffMs);
                        } catch(InterruptedException intEx) {
                            LOG.fatal("Sleep interrupted. Aborting retyr process for reload of stats manager");
                            return;
                        }
                    }
                }
                try {
                    LOG.error("Exceeded max retries for stats manager reload: " + failCount + ". Disabling the stats manager");
                    StatsManager.stop();
                } catch(Exception e) {
                    LOG.error("Unexpected failure stopping stats manager in failure path of reload. There is a problem with the stats system. Please examine configuration", e);
                }
            }
        }

        /**
         * Has the config file changed since last checked
         * @return
         */
        private boolean configChanged() {
            String configFile = StatsConfiguration.getSensorConfigScript();
            try {
                FileTime modTime = Files.getLastModifiedTime(Paths.get(configFile));
                return modTime.compareTo(this.lastModTime) > 0;
            } catch(Exception e) {
                LOG.error("Could not verify last modified time of senosr configuration file " + configFile + ". Failing config change check.");
                return false;
            }
        }

        private void scheduleNextCheck() throws Exception {
            executorService.schedule(this, StatsConfiguration.getConfigCheckInterval(), TimeUnit.SECONDS);
        }
    };
}
