package com.bot.jone.embedding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * bge-small-zh 的 BERT WordPiece 分词器（与 transformers BertTokenizer 行为对齐）。
 *
 * 关键约定（来自 tokenizer_config.json）：
 *  - do_lower_case=false  → 不转小写、不去重音
 *  - tokenize_chinese_chars=true → CJK 按单字切
 *  - max_position_embeddings=512 → 含 [CLS]/[SEP] 总长 ≤ 512，正文 ≤ 510
 *  - 不拆词（never_split 为空）
 *
 * vocab.txt 第 0 行 = [PAD]，行号即 token id（[UNK]=101, [CLS]=102, [SEP]=103）。
 */
public class BgeTokenizer {

    private final Map<String, Integer> tokenToId = new HashMap<>();
    private final int clsId;
    private final int sepId;
    private final int unkId;
    private final int maxLen;

    public BgeTokenizer(Path vocabPath) throws IOException {
        this(vocabPath, 512);
    }

    public BgeTokenizer(Path vocabPath, int maxLen) throws IOException {
        List<String> lines = Files.readAllLines(vocabPath, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            tokenToId.put(lines.get(i), i);
        }
        this.clsId = tokenToId.getOrDefault("[CLS]", 102);
        this.sepId = tokenToId.getOrDefault("[SEP]", 103);
        this.unkId = tokenToId.getOrDefault("[UNK]", 101);
        this.maxLen = maxLen;
    }

    /** 编码为 token-id 序列（已含 [CLS] 与 [SEP]，正文超长则截断）。 */
    public int[] encode(String text) {
        List<String> tokens = new ArrayList<>();
        for (String word : basicTokenize(text)) {
            tokens.addAll(wordpiece(word));
        }
        // 组装并截断：正文最多 maxLen-2 个 token
        int maxContent = Math.max(0, maxLen - 2);
        List<Integer> ids = new ArrayList<>();
        ids.add(clsId);
        int added = 0;
        for (String t : tokens) {
            if (added >= maxContent) break;
            ids.add(tokenToId.getOrDefault(t, unkId));
            added++;
        }
        ids.add(sepId);
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /** BasicTokenizer：清洗 + 中文按字 + 空白切分 + 标点剥离。 */
    private List<String> basicTokenize(String text) {
        List<String> out = new ArrayList<>();
        text = cleanText(text);
        if (text == null) return out;

        // 中文两侧加空格，使其孤立为单字 token；空白统一为空格
        StringBuilder processed = new StringBuilder();
        int cp;
        for (int i = 0; i < text.length(); i += Character.charCount(cp)) {
            cp = text.codePointAt(i);
            if (isWhitespace(cp)) {
                processed.append(' ');
            } else if (isChineseChar(cp)) {
                processed.append(' ').appendCodePoint(cp).append(' ');
            } else {
                processed.appendCodePoint(cp);
            }
        }

        for (String token : processed.toString().trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            for (String piece : splitOnPunctuation(token)) {
                if (!piece.isEmpty()) out.add(piece);
            }
        }
        return out;
    }

    /** 标点剥离：连续标点与连续非标点分别成段（与 HF _run_split_on_punc 一致）。 */
    private List<String> splitOnPunctuation(String token) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int cp;
        for (int i = 0; i < token.length(); i += Character.charCount(cp)) {
            cp = token.codePointAt(i);
            if (isPunctuation(cp)) {
                if (buf.length() > 0) { out.add(buf.toString()); buf.setLength(0); }
                out.add(new String(Character.toChars(cp)));
            } else {
                buf.appendCodePoint(cp);
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    /** WordPiece：子词合并，续接标记用 "##" 前缀；单字超 100 或无法切分则 [UNK]。 */
    private List<String> wordpiece(String token) {
        List<String> out = new ArrayList<>();
        if (token.length() > 100) { out.add("[UNK]"); return out; }
        int start = 0;
        int n = token.length();
        while (start < n) {
            int end = n;
            String cur = null;
            while (start < end) {
                String sub = token.substring(start, end);
                if (start > 0) sub = "##" + sub;
                if (tokenToId.containsKey(sub)) { cur = sub; break; }
                end--;
            }
            if (cur == null) { out.add("[UNK]"); return out; }
            out.add(cur);
            start = end;
        }
        return out;
    }

    private static String cleanText(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder();
        int cp;
        for (int i = 0; i < text.length(); i += Character.charCount(cp)) {
            cp = text.codePointAt(i);
            if (cp == 0xFFFD) continue; // 替换字符丢弃
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT) continue;
            if (isWhitespace(cp)) {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private static boolean isWhitespace(int cp) {
        if (cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D) return true;
        return Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    private static boolean isPunctuation(int cp) {
        if (isWhitespace(cp) || isChineseChar(cp)) return false;
        if (Character.isLetter(cp) || Character.isDigit(cp)) return false;
        return true; // 其余（ASCII/Unicode 标点与符号）均视为标点
    }

    private static boolean isChineseChar(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F)
                || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
