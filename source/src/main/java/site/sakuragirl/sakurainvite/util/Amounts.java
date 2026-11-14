package site.sakuragirl.sakurainvite.util;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Strict, bounded decimal amounts shared by UI, persistence and economy calls. */
public final class Amounts {
  public static final BigDecimal MAX = new BigDecimal("1000000000.00");
  private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,10}(?:\\.[0-9]{1,2})?");

  private Amounts() {}

  public static BigDecimal parse(String raw) {
    if (raw == null || raw.length() > 13 || !DECIMAL.matcher(raw).matches())
      throw new IllegalArgumentException("请输入普通十进制金额，最多两位小数");
    return validate(new BigDecimal(raw));
  }

  public static BigDecimal validate(BigDecimal amount) {
    if (amount == null || amount.signum() < 0 || amount.scale() > 2 || amount.compareTo(MAX) > 0)
      throw new IllegalArgumentException("金额范围为 0 至 1000000000，最多两位小数");
    return amount.setScale(2);
  }
}
