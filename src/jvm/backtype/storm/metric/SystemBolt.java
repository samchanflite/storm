package backtype.storm.metric;

import backtype.storm.Config;
import backtype.storm.metric.api.AssignableMetric;
import backtype.storm.metric.api.IMetric;
import backtype.storm.task.IBolt;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.tuple.Tuple;
import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.RT;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.List;
import java.util.Map;


// There is one task inside one executor for each worker of the topology.
// TaskID is always -1, therefore you can only send-unanchored tuples to co-located SystemBolt.
// This bolt was conceived to export worker stats via metrics api.
public class SystemBolt implements IBolt {
    private static Logger LOG = LoggerFactory.getLogger(SystemBolt.class);
    private static boolean _prepareWasCalled = false;

    private static class MemoryUsageMetric implements IMetric {
        IFn _getUsage;
        public MemoryUsageMetric(IFn getUsage) {
            _getUsage = getUsage;
        }
        @Override
        public Object getValueAndReset() {
            MemoryUsage memUsage = (MemoryUsage)_getUsage.invoke();
            return ImmutableMap.builder()
                    .put("maxBytes", memUsage.getMax())
                    .put("committedBytes", memUsage.getCommitted())
                    .put("initBytes", memUsage.getInit())
                    .put("usedBytes", memUsage.getUsed())
                    .put("virtualFreeBytes", memUsage.getMax() - memUsage.getUsed())
                    .put("unusedBytes", memUsage.getCommitted() - memUsage.getUsed())
                    .build();
        }
    }

    // canonically the metrics data exported is time bucketed when doing counts.
    // convert the absolute values here into time buckets.
    private static class GarbageCollectorMetric implements IMetric {
        GarbageCollectorMXBean _gcBean;
        Long _collectionCount;
        Long _collectionTime;
        public GarbageCollectorMetric(GarbageCollectorMXBean gcBean) {
            _gcBean = gcBean;
        }
        @Override
        public Object getValueAndReset() {
            Long collectionCountP = _gcBean.getCollectionCount();
            Long collectionTimeP = _gcBean.getCollectionCount();

            Map ret = null;
            if(_collectionCount!=null && _collectionTime!=null) {
                ret = ImmutableMap.builder()
                        .put("count", collectionCountP - _collectionCount)
                        .put("timeMs", collectionTimeP - _collectionTime)
                        .build();
            }

            _collectionCount = collectionCountP;
            _collectionTime = collectionTimeP;
            return ret;
        }
    }

    @Override
    public void prepare(final Map stormConf, TopologyContext context, OutputCollector collector) {
        if(_prepareWasCalled && stormConf.get(Config.STORM_CLUSTER_MODE) != "local") {
            throw new RuntimeException("A single worker should have 1 SystemBolt instance.");
        }
        _prepareWasCalled = true;

        int bucketSize = RT.intCast(stormConf.get(Config.TOPOLOGY_BUILTIN_METRICS_BUCKET_SIZE_SECS));

        final RuntimeMXBean jvmRT = ManagementFactory.getRuntimeMXBean();

        context.registerMetric("uptimeSecs", new IMetric() {
            @Override
            public Object getValueAndReset() {
                return jvmRT.getUptime()/1000.0;
            }
        }, bucketSize);

        // You can calculate topology percent uptime between T_0 to T_1 using this metric data:
        //       let s = sum topologyPartialUptimeSecs for each worker for each time buckets between T_0 and T_1
        //       topology percent uptime = s/(T_1-T_0)
        // Even if the number of workers change over time the value is still correct because I divide by TOPOLOGY_WORKERS.
        context.registerMetric("topologyPartialUptimeSecs", new IMetric() {
            private long _prevUptime = jvmRT.getUptime();
            private final double NUM_WORKERS = RT.doubleCast(stormConf.get(Config.TOPOLOGY_WORKERS));
            @Override
            public Object getValueAndReset() {
                long _nowUptime = jvmRT.getUptime();
                double ret = (_nowUptime - _prevUptime)/1000.0/NUM_WORKERS;
                _prevUptime = _nowUptime;
                return ret;
            }
        }, bucketSize);

        context.registerMetric("startTimeSecs", new IMetric() {
            @Override
            public Object getValueAndReset() {
                return jvmRT.getStartTime()/1000.0;
            }
        }, bucketSize);

        context.registerMetric("newWorkerEvent", new IMetric() {
            boolean doEvent = true;

            @Override
            public Object getValueAndReset() {
                if (doEvent) {
                    doEvent = false;
                    return 1;
                } else return 0;
            }
        }, bucketSize);

        // This is metric data global to topology, but we don't support this concept, so put it here.
        // It's very useful to have time series of TOPOLOGY_WORKERS to compare actual worker count.
        context.registerMetric("configTopologyWorkers", new IMetric() {
            @Override
            public Object getValueAndReset() {
                return stormConf.get(Config.TOPOLOGY_WORKERS);
            }
        }, bucketSize);

        final MemoryMXBean jvmMemRT = ManagementFactory.getMemoryMXBean();

        context.registerMetric("memory/heap", new MemoryUsageMetric(new AFn() {
            public Object invoke() {
                return jvmMemRT.getHeapMemoryUsage();
            }
        }), bucketSize);
        context.registerMetric("memory/nonHeap", new MemoryUsageMetric(new AFn() {
            public Object invoke() {
                return jvmMemRT.getNonHeapMemoryUsage();
            }
        }), bucketSize);

        for(GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            context.registerMetric("GC/" + b.getName().replaceAll("\\W", ""), new GarbageCollectorMetric(b), bucketSize);
        }
    }

    @Override
    public void execute(Tuple input) {
        throw new RuntimeException("Non-system tuples should never be sent to __system bolt.");
    }

    @Override
    public void cleanup() {
    }
}
