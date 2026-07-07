package com.apex.ids3.udfs;

import com.apex.ids3.util.PropertyUtils;
import com.google.common.primitives.Ints;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hive.metastore.api.Decimal;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交易日计算函数
 * @author linqz
 */
@Description(
        name = "TradingDays",
        value = "_FUNC_(date1,N) - 返回整数型（yyyyMMdd）日期前（N<0)/后(N>0) N个交易日 "
)
public class TradingDays extends UDF {
    private static final Logger log = LoggerFactory.getLogger(TradingDays.class);

    /** 进程级一次性初始化控制 */
    private static volatile boolean INITIALIZED = false;
    private static final Object INIT_LOCK = new Object();

    /** 连接池（仅用于初始化/刷新时读表） */
    private static volatile HikariDataSource DS = null;

    /** 交易日数据缓存 */
    private static final AtomicReference<int[]> ZRR = new AtomicReference<>(new int[0]);
    private static final AtomicReference<int[]> JYR = new AtomicReference<>(new int[0]);

    public TradingDays() {
        ensureInit();
    }

    /** 外部触发刷新：清缓存 + 重新全量装载 */
    public static void refreshAll() {
        synchronized (INIT_LOCK) {
            log.info("[UDF] Refresh requested: clearing caches and reloading trading days.");
            ZRR.set(new int[0]);
            JYR.set(new int[0]);
            closeDsQuietly();
            INITIALIZED = false;
            ensureInit();
        }
    }

    private static void ensureInit() {
        if (INITIALIZED) return;
        synchronized (INIT_LOCK) {
            if (INITIALIZED) return;

            try {
                // 1) 读取配置
                PropertyUtils.loadProperties();
                Properties props = PropertyUtils.getProperties();
                String driver = props.getProperty("driver");
                String url = props.getProperty("dsc_cfg_url");
                String user = props.getProperty("user");
                String pwd = props.getProperty("password");

                if (StringUtils.isBlank(url) || StringUtils.isBlank(user)) {
                    throw new IllegalStateException("JDBC config missing: driver/dsc_cfg_url/user");
                }

                log.info("[UDF] Initializing DataSource...");
                log.info("[UDF] driverClass = {}", driver);
                log.info("[UDF] jdbcUrl     = {}", url);
                log.info("[UDF] username    = {}", user);
                log.info("[UDF] password    = **** (length={})", (pwd == null ? 0 : pwd.length()));

                try {
                    Class.forName(driver);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("JDBC driver not found: " + driver, e);
                }

                // 2) 连接池配置
                HikariConfig cfg = PropertyUtils.initHikariConfigFromFile();
//                cfg.setDriverClassName(driver);
//                cfg.setJdbcUrl(url);
//                cfg.setUsername(user);
//                cfg.setPassword(pwd);
//                cfg.setMaximumPoolSize(Integer.parseInt(props.getProperty("hikari.maxPool", "4")));
//                cfg.setMinimumIdle(Integer.parseInt(props.getProperty("hikari.minIdle", "1")));
//                cfg.setConnectionTimeout(Long.parseLong(props.getProperty("hikari.connTimeoutMs", "10000")));
//                cfg.setIdleTimeout(Long.parseLong(props.getProperty("hikari.idleTimeoutMs", "300000")));
//                cfg.setMaxLifetime(Long.parseLong(props.getProperty("hikari.maxLifetimeMs", "1800000")));
//                cfg.setReadOnly(false);
//                cfg.addDataSourceProperty("cachePrepStmts", "true");
//                cfg.addDataSourceProperty("prepStmtCacheSize", "250");
//                cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

                DS = new HikariDataSource(cfg);

                // 3) 加载交易日数据
                loadTradingDays();

                INITIALIZED = true;
                log.info("[UDF] Initialization completed. Loaded {} trading days.", ZRR.get().length);
            } catch (Exception e) {
                log.error("[UDF] Initialization failed", e);
                throw new RuntimeException("UDF initialization failed", e);
            }
        }
    }

    private static void loadTradingDays() {
        int attempts = 0;
        SQLException lastError = null;

        while (attempts < 2) {
            attempts++;
            try {
                List<Integer> zrrList = new ArrayList<>();
                List<Integer> jyrList = new ArrayList<>();

                try (Connection conn = DS.getConnection();
                     PreparedStatement ps = conn.prepareStatement("select ZRR,JYR from dsc_cfg.t_xtjyr order by ZRR");
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        zrrList.add(rs.getInt(1));
                        jyrList.add(rs.getInt(2));
                    }

                    ZRR.set(Ints.toArray(zrrList));
                    JYR.set(Ints.toArray(jyrList));

                    log.info("[UDF] Successfully loaded {} trading day records", zrrList.size());
                    return;
                }
            } catch (SQLException e) {
                lastError = e;
                log.warn("[UDF] Failed to load trading days (attempt {})", attempts, e);
                try {
                    Thread.sleep(50L * attempts);
                } catch (InterruptedException ignored) {}
            }
        }

        throw new RuntimeException("Failed to load trading days after retries", lastError);
    }

    private static void closeDsQuietly() {
        try {
            if (DS != null) {
                DS.close();
                DS = null;
            }
        } catch (Exception e) {
            log.warn("[UDF] Error closing DataSource", e);
        }
    }

    // evaluate 方法实现
    public IntWritable evaluate(HiveDecimalWritable dateStr1, IntWritable iDays) {
        if (dateStr1 == null) {
            return null;
        }
        ensureInit();
        int ndate = Integer.parseInt(dateStr1.toString());
        int days = getDays(iDays);
        return new IntWritable(findTradingDay(ndate, days));
    }

    public IntWritable evaluate(Text dateStr1, IntWritable iDays) {
        if (dateStr1 == null) {
            return null;
        }
        ensureInit();
        int ndate = Integer.parseInt(dateStr1.toString());
        int days = getDays(iDays);
        return new IntWritable(findTradingDay(ndate, days));
    }

    public IntWritable evaluate(Decimal dateStr1, IntWritable iDays) {
        if (dateStr1 == null) {
            return null;
        }
        ensureInit();
        int ndate = Integer.parseInt(dateStr1.toString());
        int days = getDays(iDays);
        return new IntWritable(findTradingDay(ndate, days));
    }

    public IntWritable evaluate(DateWritable dw1, IntWritable iDays) {
        if (dw1 == null) {
            return null;
        }
        ensureInit();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(dw1.get());
        int ndate = calendar.get(Calendar.YEAR) * 10000 +
                    (calendar.get(Calendar.MONTH) + 1) * 100 +
                    calendar.get(Calendar.DAY_OF_MONTH);
        int days = getDays(iDays);
        return new IntWritable(findTradingDay(ndate, days));
    }

    public IntWritable evaluate(IntWritable iDate, IntWritable iDays) {
        if (iDate == null) {
            return null;
        }
        ensureInit();
        int ndate = iDate.get();
        int days = getDays(iDays);
        return new IntWritable(findTradingDay(ndate, days));
    }

    private int getDays(IntWritable iDays) {
        return iDays != null ? iDays.get() : 0;
    }

    private static int findTradingDay(int ndate, int days) {
        int[] zrr = ZRR.get();
        int[] jyr = JYR.get();

//        if (zrr.length == 0 || jyr.length == 0) {
//            log.warn("[UDF] Trading day data not available");
//            throw new RuntimeException("Trading day data not available");
//        }
        // 如果缓存为空或数据无效，重新加载交易日数据
        if (zrr.length == 0 || jyr.length == 0) {
            log.warn("[UDF] Trading day data not available in cache, reloading from database.");
            refreshAll(); // 从数据库重新加载数据到缓存
            zrr = ZRR.get();
            jyr = JYR.get(); // 再次获取最新的缓存数据
        }

        int idx = Arrays.binarySearch(zrr, ndate);
        int len = zrr.length;

        if (idx >= 0 && idx < len) {
            int jyr_current = jyr[idx];
            int n;

            if (ndate != jyr_current && days < 0) {
                n = (-days) - 1;
            } else {
                n = Math.abs(days);
            }

            while (n != 0) {
                idx = (days > 0) ? idx + 1 : idx - 1;
                if (idx < 0 || idx >= len) {
                    break;
                }
                int jyr_next = jyr[idx];
                if (jyr_current != jyr_next) {
                    jyr_current = jyr_next;
                    n--;
                }
            }
            return jyr_current;
        }

        // 如果索引无效，重新加载数据并再次查找
        log.warn("[UDF] Trading day not found in cache for date: {}", ndate);
        refreshAll(); // 重新加载数据到缓存
        zrr = ZRR.get();
        jyr = JYR.get(); // 再次获取最新的缓存数据

        // 再次尝试查找
        idx = Arrays.binarySearch(zrr, ndate);
        if (idx >= 0 && idx < len) {
            return jyr[idx];
        }

        // 如果仍未找到，返回原始日期

        return ndate;
    }

    public static List<Integer> getTradingDays(Integer ksrq, Integer jsrq) {
        if (ksrq == null || jsrq == null) {
            return Collections.emptyList();
        }

        int[] zrr = ZRR.get();
        int[] jyr = JYR.get();

        // 如果缓存为空或数据无效，重新加载交易日数据
        if (zrr.length == 0 || jyr.length == 0) {
            log.warn("[UDF] Trading day data not available in cache, reloading from database.");
            refreshAll(); // 从数据库重新加载数据到缓存
            zrr = ZRR.get();
            jyr = JYR.get(); // 再次获取最新的缓存数据
        }

        List<Integer> days = new ArrayList<>();
        int indexStart = Arrays.binarySearch(zrr, ksrq);
        int indexEnd = Arrays.binarySearch(zrr, jsrq);

        // 如果日期范围未找到，触发更新并重新查找
        if (indexStart < 0 || indexEnd < 0) {
            log.warn("[UDF] Date range not found in trading days: start={}, end={}", ksrq, jsrq);
            refreshAll(); // 重新加载数据到缓存
            zrr = ZRR.get();
            jyr = JYR.get(); // 再次获取最新的缓存数据
            indexStart = Arrays.binarySearch(zrr, ksrq);
            indexEnd = Arrays.binarySearch(zrr, jsrq);
        }

        // 如果仍未找到有效的交易日，返回空列表
        if (indexStart < 0 || indexEnd < 0) {
            return Collections.emptyList();
        }

        // 如果起始交易日不匹配，尝试查找下一个交易日
        if (zrr[indexStart] != jyr[indexStart]) {
            int nextTradingDay = findTradingDay(ksrq, 1);
            indexStart = Arrays.binarySearch(zrr, nextTradingDay);
            if (indexStart < 0) {
                return Collections.emptyList();
            }
        }

        // 将符合条件的交易日添加到列表中
        for (int idx = indexStart; idx <= indexEnd; idx++) {
            if (zrr[idx] == jyr[idx]) {
                days.add(jyr[idx]);
            }
        }

        return days;
    }

}
