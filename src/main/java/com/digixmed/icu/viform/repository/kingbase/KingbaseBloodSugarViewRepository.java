package com.digixmed.icu.viform.repository.kingbase;

import com.digixmed.icu.viform.config.BloodSugarPullProperties;
import com.digixmed.icu.viform.dto.BloodSugarViewDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

/**
 * 人大金仓血糖视图仓库 —— 全项目唯一访问金仓的类。
 *
 * <p>职责：
 * <ol>
 *   <li>列名元数据解析：xls 表头是中文，视图真实列名未知，首次查询前执行
 *       {@code SELECT * WHERE 1=0} 从 ResultSetMetaData 解析 7 个逻辑列，
 *       支持 yml 显式覆盖；结果进程内缓存。</li>
 *   <li>按住院号列表 × 时间窗分批查询视图，异常吞掉返回空集合，不阻塞主流程。</li>
 * </ol></p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KingbaseBloodSugarViewRepository {

    private final BloodSugarPullProperties properties;

    @Qualifier("kingbaseJdbcTemplate")
    private final JdbcTemplate jdbcTemplate;

    /** Asia/Shanghai 日历，读写 datetime 列时显式传入，不依赖 JVM 默认时区 */
    private static final Calendar CAL_SHANGHAI =
            Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));

    /** 列名解析结果缓存（逻辑名 → 实际列名），volatile 保证可见性 */
    private volatile Map<String, String> columnCache;

    /** 各逻辑列的候选名（按序匹配，不区分大小写精确比对） */
    private static final Map<String, String[]> COLUMN_CANDIDATES = buildCandidates();

    private static Map<String, String[]> buildCandidates() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("inhosNo", new String[]{"住院号", "zyh", "pat_no", "patNo", "inhos_no", "in_hos_no", "zhuyuanhao", "patient_no"});
        m.put("recordTime", new String[]{"录入时间", "lrsj", "record_time", "recordTime", "input_time", "entry_time", "check_time"});
        m.put("type", new String[]{"类型", "lx", "type", "type_name", "time_period", "timePeriod"});
        m.put("value", new String[]{"数值", "sz", "value", "val", "result", "blo_glu_val", "glu_val"});
        m.put("signer", new String[]{"签名", "qm", "sign", "signer", "signature", "nurse_name", "oper_nurse", "operat_nurse"});
        m.put("note", new String[]{"备注", "bz", "note", "remark", "remarks", "memo"});
        m.put("name", new String[]{"姓名", "xm", "name", "pat_name", "patient_name"});
        return Collections.unmodifiableMap(m);
    }

    /** 必填逻辑列（任一解析失败则本轮放弃） */
    private static final String[] REQUIRED_KEYS = {"inhosNo", "recordTime", "value", "type", "signer"};

    // ==================== 对外查询 ====================

    /**
     * 按住院号列表 + 录入时间窗查询视图。
     *
     * @param inhosNos 住院号列表（原样含前导 0）
     * @param start    窗口开始（含）
     * @param end      窗口结束（含）
     * @return 视图行列表；任何异常返回空集合
     */
    public List<BloodSugarViewDTO> findByInhosNosAndTimeWindow(List<String> inhosNos, Date start, Date end) {
        if (inhosNos == null || inhosNos.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Map<String, String> cols = resolveColumns();
            String inhosCol = quote(cols.get("inhosNo"));
            String timeCol = quote(cols.get("recordTime"));

            String viewRef = quote(properties.getViewSchema()) + "." + quote(properties.getViewName());
            List<BloodSugarViewDTO> all = new ArrayList<>();

            int batchSize = Math.max(1, properties.getBatchSize());
            for (int i = 0; i < inhosNos.size(); i += batchSize) {
                List<String> batch = inhosNos.subList(i, Math.min(i + batchSize, inhosNos.size()));
                String placeholders = batch.stream().map(b -> "?").collect(Collectors.joining(","));
                String sql = "SELECT * FROM " + viewRef
                        + " WHERE " + timeCol + " >= ?"
                        + " AND " + timeCol + " <= ?"
                        + " AND " + inhosCol + " IN (" + placeholders + ")"
                        + " ORDER BY " + timeCol;

                List<Object> args = new ArrayList<>(batch.size() + 2);
                args.add(new Timestamp(start.getTime()));
                args.add(new Timestamp(end.getTime()));
                args.addAll(batch);

                List<BloodSugarViewDTO> rows = jdbcTemplate.query(sql,
                        (ResultSet rs, int rowNum) -> mapRow(rs, cols),
                        args.toArray());
                all.addAll(rows);
            }
            log.info("[Kingbase] 查询血糖视图: inhosNos={}, rows={}, window=[{}, {}]",
                    inhosNos.size(), all.size(), start, end);
            return all;
        } catch (Exception e) {
            log.error("[Kingbase] 查询血糖视图异常，本轮返回空集合", e);
            return Collections.emptyList();
        }
    }

    // ==================== 行映射 ====================

    private BloodSugarViewDTO mapRow(ResultSet rs, Map<String, String> cols) throws java.sql.SQLException {
        BloodSugarViewDTO dto = new BloodSugarViewDTO();
        // 住院号：getString 保前导 0，禁转数字
        dto.setInhosNo(trimToNull(rs.getString(cols.get("inhosNo"))));
        // 录入时间：墙钟按 GMT+8 解释为瞬时
        Timestamp ts = rs.getTimestamp(cols.get("recordTime"), CAL_SHANGHAI);
        dto.setRecordTime(ts != null ? new Date(ts.getTime()) : null);
        // 数值 / 类型 / 备注 / 签名 / 姓名：仅 trim 原样，禁止数值解析
        dto.setValue(trimToNull(rs.getString(cols.get("value"))));
        dto.setType(trimToNull(rs.getString(cols.get("type"))));
        dto.setNote(trimToNull(rs.getString(cols.get("note"))));
        dto.setSigner(trimToNull(rs.getString(cols.get("signer"))));
        dto.setName(trimToNull(rs.getString(cols.get("name"))));
        return dto;
    }

    // ==================== 列名解析 ====================

    /**
     * 解析视图列名（带进程内缓存）。
     *
     * <p>解析顺序：yml 显式覆盖 &gt; 候选名列表（不区分大小写精确匹配）。</p>
     *
     * @return 逻辑名 → 实际列名
     * @throws IllegalStateException 必填列任一解析失败
     */
    private Map<String, String> resolveColumns() {
        Map<String, String> cached = columnCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (columnCache != null) {
                return columnCache;
            }
            // 1. 取实际列名
            List<String> actualColumns = fetchActualColumnNames();
            // 2. 逐逻辑列解析
            Map<String, String> resolved = new LinkedHashMap<>();
            BloodSugarPullProperties.Columns override = properties.getColumns();
            for (Map.Entry<String, String[]> entry : COLUMN_CANDIDATES.entrySet()) {
                String key = entry.getKey();
                String overrideVal = getOverride(override, key);
                if (StringUtils.hasText(overrideVal)) {
                    resolved.put(key, overrideVal);
                    continue;
                }
                String found = matchCandidate(actualColumns, entry.getValue());
                if (found != null) {
                    resolved.put(key, found);
                }
            }
            // 3. 必填列校验
            List<String> missing = new ArrayList<>();
            for (String req : REQUIRED_KEYS) {
                if (!resolved.containsKey(req)) {
                    missing.add(req);
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "血糖视图必填列解析失败: " + missing + "，实际列=" + actualColumns
                                + "，请在 bloodsugar-pull.columns.* 显式配置");
            }
            // 4. 打印解析结果
            log.info("[Kingbase] 血糖视图列名解析结果: {}", resolved);
            columnCache = Collections.unmodifiableMap(resolved);
            return columnCache;
        }
    }

    /** 获取实际列名列表（元数据发现） */
    private List<String> fetchActualColumnNames() {
        String viewRef = quote(properties.getViewSchema()) + "." + quote(properties.getViewName());
        return jdbcTemplate.query("SELECT * FROM " + viewRef + " WHERE 1 = 0", (ResultSet rs) -> {
            ResultSetMetaData md = rs.getMetaData();
            List<String> cols = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(md.getColumnLabel(i));
            }
            return cols;
        });
    }

    /** 按候选名列表匹配（不区分大小写精确） */
    private String matchCandidate(List<String> actualColumns, String[] candidates) {
        for (String cand : candidates) {
            for (String actual : actualColumns) {
                if (actual != null && actual.equalsIgnoreCase(cand)) {
                    return actual;
                }
            }
        }
        return null;
    }

    /** 取 yml 覆盖值 */
    private String getOverride(BloodSugarPullProperties.Columns c, String key) {
        if (c == null) return null;
        switch (key) {
            case "name": return c.getName();
            case "inhosNo": return c.getInhosNo();
            case "recordTime": return c.getRecordTime();
            case "type": return c.getType();
            case "value": return c.getValue();
            case "note": return c.getNote();
            case "signer": return c.getSigner();
            default: return null;
        }
    }

    // ==================== 工具 ====================

    /** 双引号包裹标识符（中文/保留字/大小写混合安全） */
    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
