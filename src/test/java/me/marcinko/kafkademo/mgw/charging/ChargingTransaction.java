package me.marcinko.kafkademo.mgw.charging;

import java.time.LocalDate;
import java.util.Objects;

public class ChargingTransaction {
	private final String reservationId;
	private final LocalDate localDate;
	private final Long partnerId;
	private final String billingText;
	private final ChargingSubscriberType subscriberType;

	public ChargingTransaction(String reservationId, LocalDate localDate, Long partnerId, String billingText, ChargingSubscriberType subscriberType) {
		this.reservationId = reservationId;
		this.localDate = localDate;
		this.partnerId = partnerId;
		this.billingText = billingText;
		this.subscriberType = subscriberType;
	}

	public String getReservationId() {
		return reservationId;
	}

	public LocalDate getLocalDate() {
		return localDate;
	}

	public Long getPartnerId() {
		return partnerId;
	}

	public String getBillingText() {
		return billingText;
	}

	public ChargingSubscriberType getSubscriberType() {
		return subscriberType;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ChargingTransaction)) {
			return false;
		}
		ChargingTransaction that = (ChargingTransaction) o;
		return Objects.equals(reservationId, that.reservationId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(reservationId);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("ChargingTransaction{");
		sb.append("reservationId='").append(reservationId).append('\'');
		sb.append(", localDate=").append(localDate);
		sb.append(", partnerId=").append(partnerId);
		sb.append(", billingText='").append(billingText).append('\'');
		sb.append(", subscriberType=").append(subscriberType);
		sb.append('}');
		return sb.toString();
	}
}
