package com.taskmanager.util;

import net.sourceforge.pinyin4j.PinyinHelper;

/**
 * 拼音工具：中文串 → 拼音首字母缩写（用于搜索匹配）。
 * <p>
 * 基于 pinyin4j（BSD 许可）。多音字取第一个读音（首字母缩写搜索的尽力而为语义）；
 * 非汉字字符（字母/数字/符号）保留原样转小写，便于混合匹配。
 */
public final class PinyinUtil {
	private PinyinUtil() {
	}

	/**
	 * 中文串 → 拼音首字母缩写（如「服务端主循环」→「fwdxh」，「鱿鱼」→「yy」）。
	 * 非汉字字符原样转小写保留。
	 */
	public static String toFirstLetters(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= 0x4E00 && c <= 0x9FA5) { // CJK 基本区汉字
				String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(c);
				if (pinyins != null && pinyins.length > 0 && !pinyins[0].isEmpty()) {
					sb.append(Character.toLowerCase(pinyins[0].charAt(0)));
				}
			} else {
				sb.append(Character.toLowerCase(c));
			}
		}
		return sb.toString();
	}
}
