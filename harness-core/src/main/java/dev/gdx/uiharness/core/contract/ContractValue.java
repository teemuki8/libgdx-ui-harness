package dev.gdx.uiharness.core.contract;

import java.math.BigDecimal;
import java.util.Objects;

/** Closed JSON-compatible typed value union used by state and action contracts. */
public sealed interface ContractValue permits ContractValue.NullValue, ContractValue.BooleanValue,
        ContractValue.IntegerValue, ContractValue.DecimalValue, ContractValue.TextValue {
    static NullValue nullValue() {
        return NullValue.INSTANCE;
    }

    static BooleanValue bool(boolean value) {
        return new BooleanValue(value);
    }

    static IntegerValue integer(long value) {
        return new IntegerValue(value);
    }

    static DecimalValue decimal(BigDecimal value) {
        return new DecimalValue(value);
    }

    static TextValue text(String value) {
        return new TextValue(value);
    }

    /** Explicit null, distinct from the text value {@code "null"}. */
    enum NullValue implements ContractValue {
        INSTANCE
    }

    record BooleanValue(boolean value) implements ContractValue {}

    record IntegerValue(long value) implements ContractValue {}

    /** Finite canonical decimal value. */
    record DecimalValue(BigDecimal value) implements ContractValue {
        public DecimalValue {
            value = Objects.requireNonNull(value, "value").stripTrailingZeros();
        }
    }

    record TextValue(String value) implements ContractValue {
        public TextValue {
            Objects.requireNonNull(value, "value");
            if (value.length() > ContractSupport.MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("value exceeds contract string limit");
            }
        }
    }
}
