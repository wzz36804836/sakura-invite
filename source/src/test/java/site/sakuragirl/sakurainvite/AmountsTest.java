package site.sakuragirl.sakurainvite;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import site.sakuragirl.sakurainvite.util.Amounts;

class AmountsTest {
  @Test
  void decimalAndBoundaries() {
    assertEquals(new BigDecimal("0.00"), Amounts.parse("0"));
    assertEquals(new BigDecimal("1000000000.00"), Amounts.parse("1000000000.00"));
    assertEquals(new BigDecimal("12.30"), Amounts.parse("12.3"));
  }

  @Test
  void rejectScientificAndUnboundedInput() {
    for (String raw :
        new String[] {
          "1e309",
          "1e100000000",
          "-1",
          "1.001",
          "NaN",
          "Infinity",
          "1000000001",
          "1".repeat(2000),
          "+1",
          ".5",
          ""
        }) assertThrows(IllegalArgumentException.class, () -> Amounts.parse(raw), raw);
    assertThrows(IllegalArgumentException.class, () -> Amounts.validate(new BigDecimal("1e309")));
  }
}
