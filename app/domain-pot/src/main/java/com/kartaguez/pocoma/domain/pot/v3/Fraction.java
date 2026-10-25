package com.kartaguez.pocoma.domain.pot.v3;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Objects;

/** Exact canonical rational; no storage or presentation precision limit. */
public record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {
    public static final Fraction ZERO = new Fraction(0, 1);
    public static final Fraction ONE = new Fraction(1, 1);
    public Fraction {
        Objects.requireNonNull(numerator); Objects.requireNonNull(denominator);
        if (denominator.signum() == 0) throw new IllegalArgumentException("Zero denominator");
        if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
        var gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd); denominator = denominator.divide(gcd);
    }
    public Fraction(long numerator, long denominator) { this(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)); }
    public static Fraction decimal(String lexical) {
        var decimal = new BigDecimal(lexical);
        return decimal.scale() < 0
                ? new Fraction(decimal.unscaledValue().multiply(BigInteger.TEN.pow(Math.negateExact(decimal.scale()))), BigInteger.ONE)
                : new Fraction(decimal.unscaledValue(), BigInteger.TEN.pow(decimal.scale()));
    }
    public Fraction add(Fraction other) { return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)), denominator.multiply(other.denominator)); }
    public Fraction subtract(Fraction other) { return add(new Fraction(other.numerator.negate(), other.denominator)); }
    public Fraction multiply(Fraction other) { return new Fraction(numerator.multiply(other.numerator), denominator.multiply(other.denominator)); }
    public Fraction divide(Fraction other) {
        if (other.numerator.signum() == 0) throw new ArithmeticException("Division by zero");
        return new Fraction(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }
    public int compareTo(Fraction other) { return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator)); }
}
