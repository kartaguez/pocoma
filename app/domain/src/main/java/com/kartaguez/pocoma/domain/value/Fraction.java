package com.kartaguez.pocoma.domain.value;

import java.math.BigInteger;
import java.util.Objects;

public final class Fraction implements Comparable<Fraction> {

	public static final Fraction ZERO = new Fraction(0, 1);
	public static final Fraction ONE = new Fraction(1, 1);

	private final long numerator;
	private final long denominator;

	public Fraction(long numerator, long denominator) {
		Fraction normalized = normalize(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
		this.numerator = normalized.numerator;
		this.denominator = normalized.denominator;
	}

	private Fraction(long numerator, long denominator, boolean normalized) {
		this.numerator = numerator;
		this.denominator = denominator;
	}

	public static Fraction of(long numerator, long denominator) {
		return new Fraction(numerator, denominator);
	}

	public long numerator() {
		return numerator;
	}

	public long denominator() {
		return denominator;
	}

	public Fraction add(Fraction other) {
		Objects.requireNonNull(other, "other must not be null");
		BigInteger resultNumerator = BigInteger.valueOf(numerator)
			.multiply(BigInteger.valueOf(other.denominator))
			.add(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
		BigInteger resultDenominator = BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator));

		return from(resultNumerator, resultDenominator);
	}

	public Fraction subtract(Fraction other) {
		Objects.requireNonNull(other, "other must not be null");
		BigInteger resultNumerator = BigInteger.valueOf(numerator)
			.multiply(BigInteger.valueOf(other.denominator))
			.subtract(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
		BigInteger resultDenominator = BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator));

		return from(resultNumerator, resultDenominator);
	}

	public Fraction multiply(Fraction other) {
		Objects.requireNonNull(other, "other must not be null");
		BigInteger resultNumerator = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.numerator));
		BigInteger resultDenominator = BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator));

		return from(resultNumerator, resultDenominator);
	}

	public Fraction divide(Fraction other) {
		Objects.requireNonNull(other, "other must not be null");
		if (other.numerator == 0) {
			throw new ArithmeticException("Cannot divide by zero");
		}

		BigInteger resultNumerator = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.denominator));
		BigInteger resultDenominator = BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.numerator));

		return from(resultNumerator, resultDenominator);
	}

	@Override
	public int compareTo(Fraction other) {
		Objects.requireNonNull(other, "other must not be null");
		BigInteger left = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.denominator));
		BigInteger right = BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator));

		return left.compareTo(right);
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof Fraction fraction)) {
			return false;
		}
		return numerator == fraction.numerator && denominator == fraction.denominator;
	}

	@Override
	public int hashCode() {
		return Objects.hash(numerator, denominator);
	}

	@Override
	public String toString() {
		return numerator + "/" + denominator;
	}

	private static Fraction from(BigInteger numerator, BigInteger denominator) {
		return normalize(numerator, denominator);
	}

	private static Fraction normalize(BigInteger numerator, BigInteger denominator) {
		if (denominator.signum() == 0) {
			throw new IllegalArgumentException("Denominator must not be zero");
		}
		if (numerator.signum() == 0) {
			return new Fraction(0, 1, true);
		}
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}

		BigInteger greatestCommonDivisor = numerator.gcd(denominator);
		BigInteger normalizedNumerator = numerator.divide(greatestCommonDivisor);
		BigInteger normalizedDenominator = denominator.divide(greatestCommonDivisor);

		return new Fraction(normalizedNumerator.longValueExact(), normalizedDenominator.longValueExact(), true);
	}
}
