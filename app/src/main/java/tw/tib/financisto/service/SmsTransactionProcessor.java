package tw.tib.financisto.service;

import android.content.Context;
import android.util.Log;

import tw.tib.financisto.R;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Payee;
import tw.tib.financisto.model.Project;
import tw.tib.financisto.model.SmsTemplate;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionStatus;
import tw.tib.financisto.utils.StringUtil;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.math.BigDecimal.ZERO;
import static java.util.regex.Pattern.DOTALL;
import static tw.tib.financisto.service.SmsTransactionProcessor.Placeholder.*;

public class SmsTransactionProcessor {
    private static final String TAG = SmsTransactionProcessor.class.getSimpleName();
    static BigDecimal HUNDRED = new BigDecimal(100);

    private final DatabaseAdapter db;

    public SmsTransactionProcessor(DatabaseAdapter db) {
        this.db = db;
    }

    /**
     * 一次比對的結果。「沒有樣板比中」與「比中了卻沒記成一筆」是兩回事，處置也不同
     * （前者要改樣板，後者八成是對不到帳戶），但兩者都只是「回 null」——
     * UI 因此把後者講成「比不中」，把人指向錯的地方。2026-08-09 實地踩到後拆開。
     */
    public static class Result {
        /** 記成的交易；null＝沒記成。 */
        public Transaction transaction;
        /** 有樣板比中內文（不論最後有沒有記成一筆）。 */
        public boolean matched;
        /** matched 但沒記成時：比中的樣板從內文抽到的卡號末四碼（沒抽到就 null）。 */
        public String accountDigits;
    }

    /**
     * Parses sms and adds new transaction if it matches any sms template
     * @return new transaction or null if not matched/parsed
     */
    public Transaction createTransactionBySms(Context context, String addr, String fullSmsBody, TransactionStatus status, boolean updateNote) {
        return process(context, addr, fullSmsBody, status, updateNote).transaction;
    }

    /** 同 {@link #createTransactionBySms}，但回報「為什麼沒記成」——給要對人解釋的 UI 用。 */
    public Result process(Context context, String addr, String fullSmsBody, TransactionStatus status, boolean updateNote) {
        return process(context, addr, fullSmsBody, status, updateNote, 0);
    }

    /**
     * @param fallbackDateTime 樣板沒抽到 {@code {{g}}} 時間戳時，要記成的交易時間
     *        （0＝用交易物件預設的「當下」）。
     *
     *        給「事後拿一則舊通知來記帳」用：通知日誌留 7 天，從列表點三天前那則通知套樣板，
     *        交易時間該是通知發出的時間、不是按下去的當下。背景自動入帳不需要（收到就記，
     *        當下 ≈ 通知時間），所以走上面那個不帶時間的版本。
     *        樣板自己抽到的 {{g}} 優先——那是銀行給的，比通知時間準。
     */
    public Result process(Context context, String addr, String fullSmsBody, TransactionStatus status,
                          boolean updateNote, long fallbackDateTime) {
        Result res = new Result();
        List<SmsTemplate> addrTemplates = db.getSmsTemplatesByNumber(addr);
        for (final SmsTemplate template : addrTemplates) {
            String[] match = findTemplateMatches(template.template, fullSmsBody);
            if (match != null) {
                Log.d(TAG, format("Found template \"%s\" with matches \"%s\"", template, Arrays.toString(match)));
                res.matched = true;

                String account = match[ACCOUNT.ordinal()];
                if (res.accountDigits == null) res.accountDigits = account;
                String account_name = match[ACCOUNT_NAME.ordinal()];
                String transfer_to_account_name = match[TRANSFER_TO_ACCOUNT_NAME.ordinal()];
                String parsedPrice = match[PRICE.ordinal()];
                String text = match[TEXT.ordinal()];
                String greedy_text = match[GREEDY_TEXT.ordinal()];
                String payeeText =  match[PAYEE.ordinal()];
                String projectText = match[PROJECT.ordinal()];
                String currencyText = match[CURRENCY.ordinal()];
                String timestampMillisText = match[TIMESTAMP_MILLIS.ordinal()];
                String categoryIdText = match[CATEGORY_ID.ordinal()];
                if (text == null && greedy_text != null) {
                    text = greedy_text;
                }
                String note = "";
                if (template.note != null && !template.note.isEmpty()) {
                    if (text == null) {
                        text = "";
                    }
                    note = template.note.replace("{{t}}", text);
                }
                else if (text != null) {
                    note = text;
                }
                else if (updateNote) {
                    note = fullSmsBody;
                }
                try {
                    BigDecimal price = toBigDecimal(parsedPrice);
                    Transaction t = createNewTransaction(context, addr, fullSmsBody, template, currencyText, price, account, account_name,
                            transfer_to_account_name, payeeText, projectText, note, timestampMillisText, categoryIdText,
                            status, fallbackDateTime);
                    if (t != null) {
                        res.transaction = t;
                        return res;
                    }
                    // 比中卻建不成（多半是對不到帳戶）時**繼續試下一條樣板**：原本這裡直接
                    // return，於是同一個標題下第一條比中的樣板會把後面的全擋掉——舊的壞樣板
                    // 讓新存的好樣板永遠沒機會跑，是實際踩過的坑
                } catch (Exception e) {
                    Log.e(TAG, format("Failed to parse price value: \"%s\"", parsedPrice), e);
                }
            }
        }
        return res;
    }

    /**
     * from <a href="https://stackoverflow.com/a/41697399/365675>SO</a>
     */
    static public BigDecimal toBigDecimal(final String value) {
        if (value != null) {
            final String EMPTY = "";
            final char COMMA = ',';
            final String POINT_AS_STRING = ".";
            final char POINT = '.';
            final String COMMA_AS_STRING = ",";

            String trimmed = value.trim();
            boolean negativeNumber =
                ((trimmed.contains("(") && trimmed.contains(")"))
                    || trimmed.endsWith("-")
                    || trimmed.startsWith("-"));

            String parsedValue = value.replaceAll("[^0-9,.]", EMPTY);

            if (negativeNumber) parsedValue = "-" + parsedValue;

            int lastPointPosition = parsedValue.lastIndexOf(POINT);
            int lastCommaPosition = parsedValue.lastIndexOf(COMMA);

            //handle '1423' case, just a simple number
            if (lastPointPosition == -1 && lastCommaPosition == -1) {
                return new BigDecimal(parsedValue);
            }
            //handle '45.3' and '4.550.000' case, only points are in the given String
            if (lastPointPosition > -1 && lastCommaPosition == -1) {
                int firstPointPosition = parsedValue.indexOf(POINT);
                if (firstPointPosition != lastPointPosition)
                    return new BigDecimal(parsedValue.replace(POINT_AS_STRING, EMPTY));
                else
                    return new BigDecimal(parsedValue);
            }
            //handle '45,3' and '4,550,000' case, only commas are in the given String
            //assume decimal part only have at most 2 digits
            if (lastPointPosition == -1 && lastCommaPosition > -1) {
                int firstCommaPosition = parsedValue.indexOf(COMMA);
                if (firstCommaPosition != lastCommaPosition ||
                    lastCommaPosition < (parsedValue.length() - 3))
                    return new BigDecimal(parsedValue.replace(COMMA_AS_STRING, EMPTY));
                else
                    return new BigDecimal(parsedValue.replace(COMMA, POINT));
            }
            //handle '2.345,04' case, points are in front of commas
            if (lastPointPosition < lastCommaPosition) {
                parsedValue = parsedValue.replace(POINT_AS_STRING, EMPTY);
                return new BigDecimal(parsedValue.replace(COMMA, POINT));
            }
            //handle '2,345.04' case, commas are in front of points
            if (lastCommaPosition < lastPointPosition) {
                parsedValue = parsedValue.replace(COMMA_AS_STRING, EMPTY);
                return new BigDecimal(parsedValue);
            }
        }
        throw new NumberFormatException("Unexpected number format. Cannot convert '" + value + "' to BigDecimal.");
    }

    private Transaction createNewTransaction(Context context, String sender, String body,
        SmsTemplate smsTemplate,
        String currency, BigDecimal price,
        String accountDigits,
        String accountName,
        String transferToAccountName,
        String payeeText,
        String projectText,
        String note,
        String timestampMillis,
        String categoryIdText,
        TransactionStatus status,
        long fallbackDateTime)
    {
        Transaction res = null;
        long accountId = 0;
        long transferToAccountId = 0;
        if (accountName != null) {
            accountId = db.getEntityIdByTitle(Account.class, accountName);
        }
        if (accountId == 0) {
            accountId = findAccount(accountDigits, smsTemplate.accountId);
        }
        if (transferToAccountName != null) {
            transferToAccountId = db.getEntityIdByTitle(Account.class, transferToAccountName);
        }
        if (transferToAccountId == 0 && smsTemplate.toAccountId != -1) {
            transferToAccountId = smsTemplate.toAccountId;
        }
        if (price.compareTo(ZERO) > 0 && accountId > 0) {
            // 收款人／專案是「找不到就新建」，所以必須等確定要記帳了才做：擺在帳戶檢查
            // 之前的話，每一則比中但記不成的通知都會在收款人表塞一筆垃圾（{{e}} 抓歪時
            // 更明顯——Nintendo 抓成 N 也照樣建出一個叫「N」的收款人）
            Payee payee = null;
            Project project = null;
            if (payeeText != null) {
                payee = db.findOrInsertEntityByTitle(Payee.class, payeeText);
            }
            if (projectText != null) {
                project = db.findOrInsertEntityByTitle(Project.class, projectText);
            }
            Log.d(TAG, format("payee=%s project=%s template.payeeId=%s template.projectId=%s",
                    payee, project, smsTemplate.payeeId, smsTemplate.projectId));

            res = new Transaction();
            res.isTemplate = 0;
            res.fromAccountId = accountId;

            // {{g}}（銀行給的時間戳）優先；沒有才用呼叫端給的時間；兩者都沒有就留預設的當下
            if (timestampMillis != null) {
                res.dateTime = Long.parseLong(timestampMillis);
            }
            else if (fallbackDateTime > 0) {
                res.dateTime = fallbackDateTime;
            }

            if (payee != null) {
                res.payeeId = payee.id;
                res.categoryId = payee.lastCategoryId;
            }
            else if (smsTemplate.payeeId != Payee.EMPTY.id) {
                Payee templatePayee = db.get(Payee.class, smsTemplate.payeeId);
                if (templatePayee != null) {
                    res.payeeId = smsTemplate.payeeId;
                    res.categoryId = templatePayee.lastCategoryId;
                }
            }

            if (project != null) {
                res.projectId = project.id;
            } else if (smsTemplate.projectId != Project.NO_PROJECT_ID) {
                Project templateProject = db.get(Project.class, smsTemplate.projectId);
                if (templateProject != null) {
                    res.projectId = smsTemplate.projectId;
                }
            }

            long fromAmount = (smsTemplate.isIncome ? 1 : -1) * Math.abs(price.multiply(HUNDRED).longValue());
            if (currency != null) {
                long currencyId = db.findCurrencyByName(currency);
                if (currencyId != 0) {
                    Account a = db.getAccount(accountId);
                    if (a.currency.id != currencyId) {
                        res.originalCurrencyId = currencyId;
                        res.originalFromAmount = fromAmount;
                    }
                }
            }
            if (res.originalCurrencyId == 0) {
                res.fromAmount = fromAmount;
            }
            if (transferToAccountId != 0) {
                res.toAccountId = transferToAccountId;
                res.toAmount = (smsTemplate.isIncome ? -1 : 1) * Math.abs(price.multiply(HUNDRED).longValue());
            }
            res.note = note;
            if (smsTemplate.categoryId != 0) {
                res.categoryId = smsTemplate.categoryId;
            }
            // {{k}} 帶的分類最優先：它是「這一筆」的判斷，比樣板綁死的那個與受款人記著的
            // 上一次都具體。抓到的 id 必須是帳本裡真的存在的分類——訊息產生端讀的是帳本
            // 備份，備份比 app 舊一點的時候可能指到已刪掉的分類。
            //
            // 對不到就當作沒帶（留空），交易照記：分類錯不影響金額與帳戶，而留空正好退回
            // 原本的行為（分類事後在 blotter 篩出來補），在畫面上看得見。相對地「整筆不記」
            // 對記帳的代價太大——為了一個分類丟掉一整筆是不划算的交換。
            long placeholderCategoryId = parseCategoryId(categoryIdText);
            if (placeholderCategoryId > 0 && db.getCategory(placeholderCategoryId) != null) {
                res.categoryId = placeholderCategoryId;
            }
            res.status = status;
            long id = db.insertOrUpdate(res);
            res.id = id;

            Log.i(TAG, format("Transaction `%s` was added with id=%s", res, id));
        } else {
            db.log(context.getString(R.string.sms_tpl_error_log, sender, body, smsTemplate.template));
        }
        return res;
    }

    /**
     * {@code {{k}}} 抓到的字串 → 分類 id。0 / 空 / 不是數字都回 0＝不指定。
     *
     * 全形與其他書寫系統的數字會照數值解讀（Android 的 regex 是 ICU 實作、{@code \d} 抓的是
     * Unicode Nd，而 {@code parseLong} 走 {@code Character.digit} 一樣解得出來）——這是
     * 安全的方向，不要改嚴。try/catch 是為了「真的不是數字」時不讓整筆交易掉在例外裡不見。
     */
    static long parseCategoryId(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, format("{{k}} 抓到不是十進位數字的內容：\"%s\"，當作沒帶分類", text));
            return 0;
        }
    }

    private long findAccount(String accountLastDigits, long defaultId) {
        long res = defaultId;
        long matchedAccId = findAccountByCardNumber(accountLastDigits);
        if (matchedAccId > 0) {
            res = matchedAccId;
            Log.d(TAG, format("Found account %s by sms match: `%s`", matchedAccId, accountLastDigits));
        }
        return res;
    }

    private long findAccountByCardNumber(String accountEnding) {
        long res = -1;

        if (!StringUtil.isEmpty(accountEnding)) {
            List<Long> accountIds = db.findAccountsByNumber(accountEnding);
            if (!accountIds.isEmpty()) {
                res = accountIds.get(0);
                if (accountIds.size() > 1) {
                    Log.e(TAG, format("Accounts ending with `%s` - more than one!", accountEnding));
                }
            }
        }
        return res;
    }

    /**
     * Finds template matches or null if none
     * ex. ECMC<:A:> <:D:> покупка <:P:> TEREMOK <::>Баланс: <:B:>р
     */
    public static String[] findTemplateMatches(String template, final String sms) {
        Log.d(TAG, "findTemplateMatches template=\"" + template + "\", sms=\"" + sms + "\"");

        String[] results = null;
        template = preprocessPatterns(template);
        final int[] phIndexes = findPlaceholderIndexes(template);

        if (phIndexes != null) {
            // escape regex characters (i.e. can't use regex in template)
            template = template.replaceAll("([.\\[\\]{}()*+\\-?^$|])", "\\\\$1");
            for (int i = 0; i < phIndexes.length; i++) {
                if (phIndexes[i] != -1) {
                    Placeholder placeholder = Placeholder.values()[i];
                    template = template.replace(placeholder.code, placeholder.regexp);
                }
            }
            template = template.replace(ANY.code, ANY.regexp);
            Log.d(TAG, "template=" + template);

            Matcher matcher = Pattern.compile(template, DOTALL).matcher(sms);
            if (matcher.find()) {
                results = new String[Placeholder.values().length];
                for (int i = 0; i < phIndexes.length; i++) {
                    final int groupNum = phIndexes[i] + 1;
                    if (groupNum > 0) {
                        results[i] = matcher.group(groupNum);
                    }
                }
            }
        }
        return results;
    }

    private static String preprocessPatterns(String template) {
        String res = template;
        for (Placeholder ph : Placeholder.values()) {
            if (ph.synonyms.length > 0) {
                for (String synonym : ph.synonyms) {
                    res = StringUtil.replaceAllIgnoreCase(res, synonym, ph.code);
                }
            }
        }
        return res;
    }

    /**
     * @return null if not found Price placeholder
     */
    static int[] findPlaceholderIndexes(String template) {
        Map<Integer, Placeholder> sorted = new TreeMap<>();
        boolean foundPrice = false;
        for (Placeholder p : Placeholder.values()) {
            int i = template.indexOf(p.code);
            if (i >= 0) {
                if (p == PRICE) {
                    foundPrice = true;
                }
                if (p != ANY) {
                    sorted.put(i, p);
                }
            }
        }
        int[] result = null;
        if (foundPrice) {
            result = new int[Placeholder.values().length];
            Arrays.fill(result, -1);
            int i = 0;
            for (Placeholder p : sorted.values()) {
                result[p.ordinal()] = i++;
            }
        }
        return result;
    }


    public enum Placeholder {
        /**
         * Please note that order of constants is very important,
         * and keep it in alphabetical way
         */
        ANY("<::>", ".*?", "{{*}}"),
        ACCOUNT("<:A:>", "\\s{0,3}(\\d{4})\\s{0,3}", "{{a}}"),
        BALANCE("<:B:>", "\\s{0,3}([\\d\\.,\\-\\+\\']+(?:[\\d \\xA0\\.,]+?)*)\\s{0,3}", "{{b}}"),
        ACCOUNT_NAME("<:C:>", "(\\S+?)", "{{c}}"),
        DATE("<:D:>", "\\s{0,3}(\\d[\\d\\. /:-]{12,14}\\d)\\s*?", "{{d}}"),
        PAYEE("<:E:>", "(\\S+?)", "{{e}}"),
        CURRENCY("<:F:>", "([A-Z]{3})", "{{f}}"),
        TIMESTAMP_MILLIS("<:G:>", "(\\d{1,13})", "{{g}}"),
        // 分類 id（不是分類名）。給「訊息由會判斷的一端產生」的來源用——它讀得到帳本、
        // 挑得出確切那一個分類，所以直接帶 id：分類名在樹的不同分支可以重複，而全路徑
        // 含空白（"數位服務 > 訂閱服務"），(\S+?) 那類佔位符接不住。0＝不指定。
        // 對照 app 內的 AI 記帳，那條路也是模型回 category_id（見 EntityContextBuilder）。
        CATEGORY_ID("<:K:>", "(\\d{1,9})", "{{k}}"),
        PRICE("<:P:>", BALANCE.regexp, "{{p}}"),
        PROJECT("<:R:>", "(\\S+?)", "{{r}}"),
        TEXT("<:T:>", "(.*?)", "{{t}}"),
        GREEDY_TEXT("<:U:>", "(.*)", "{{u}}"),
        // (\w+?) fails on account titles containing punctuation such as "-" or "()":
        // those are not word characters, so the template does not match at all and no
        // transaction is created. Use (\S+?), consistent with ACCOUNT_NAME / PAYEE /
        // PROJECT above; \S is a superset of \w, so existing templates keep working.
        // Covered by PlaceholderCaptureTest in androidTest — it has to run on a device,
        // because Android's regex is ICU-backed and a desktop JVM answers differently.
        TRANSFER_TO_ACCOUNT_NAME("<:X:>", "(\\S+?)", "{{x}}");

        public String code;
        public String regexp;
        public String[] synonyms;

        Placeholder(String code, String regexp, String ... synonyms) {
            this.code = code;
            this.regexp = regexp;
            this.synonyms = synonyms;
        }
    }
}
