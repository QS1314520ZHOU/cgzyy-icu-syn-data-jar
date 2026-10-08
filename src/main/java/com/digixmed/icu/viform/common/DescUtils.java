package com.digixmed.icu.viform.common;

/**
 * 护理记录描述（{@code NurseRecords.desc}）结尾标点工具。
 *
 * <p>同步写入护理记录的描述统一以「。」结尾，不再以「；」「;」结尾；
 * 分段之间仍然用「」分隔，只处理整段文字的最后一个字符。</p>
 */
public final class DescUtils {

    /** 结尾需要去掉的标点与空白：句号、全半角分号、顿号、逗号 */
    private static final String TRAILING_PUNCT = "[。；;、，,\\s]+$";

    private DescUtils() {
    }

    /**
     * 去掉结尾的标点与空白。
     *
     * <p>例：「皮肤情况：压红；」→「皮肤情况：压红」；
     * 「胃管：置入长度:55cm;」→「胃管：置入长度:55cm」。</p>
     *
     * @param desc 原始描述，可为 null
     * @return 去掉结尾标点后的文本；入参为 null 时返回 ""
     */
    public static String stripEnding(String desc) {
        if (desc == null) {
            return "";
        }
        return desc.trim().replaceAll(TRAILING_PUNCT, "");
    }

    /**
     * 统一以「。」结尾：先去掉结尾的「」「;」「。」等标点，再补一个「」。
     *
     * <p>例：「GCS评分：E3V5M6分；疼痛评分：NRS-0分」→「GCS评分：E3V5M6分；疼痛评分：NRS-0分。」</p>
     *
     * @param desc 原始描述，可为 null
     * @return 以「。」结尾的描述；正文为空时返回 ""
     */
    public static String withPeriodEnding(String desc) {
        String body = stripEnding(desc);
        return body.isEmpty() ? "" : body + "。";
    }

    /**
     * 合并两段描述：去掉左段结尾标点后用「」相连，整体以「」结尾。
     *
     * <p>例：「患者无不适。」+「GCS评分：E3V5M6分」
     * →「患者无不适；GCS评分：E3V5M6分。」，
     * 避免合并出「。；」这种连续标点。</p>
     *
     * @param left  左段描述（已有记录内容），可为 null
     * @param right 右段描述（本次同步内容），可为 null
     * @return 合并后的描述，以「。」结尾；两段正文都为空时返回 ""
     */
    public static String merge(String left, String right) {
        String l = stripEnding(left);
        String r = stripEnding(right);
        if (l.isEmpty()) {
            return r.isEmpty() ? "" : r + "。";
        }
        if (r.isEmpty()) {
            return l + "。";
        }
        return l + "；" + r + "。";
    }
}
