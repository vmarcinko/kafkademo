package me.marcinko.kafkademo.mgw.message;

import java.time.LocalDate;
import java.util.Objects;

public class MessageCount {
	private final LocalDate localDate;
	private final Long partnerId;
	private final boolean mms;
	private final String shortCode;
	private final MessageCountType type;
	private final long count;

	public MessageCount(LocalDate localDate, Long partnerId, boolean mms, String shortCode, MessageCountType type, long count) {
		this.localDate = localDate;
		this.partnerId = partnerId;
		this.mms = mms;
		this.shortCode = shortCode;
		this.type = type;
		this.count = count;
	}

	public LocalDate getLocalDate() {
		return localDate;
	}

	public Long getPartnerId() {
		return partnerId;
	}

	public boolean isMms() {
		return mms;
	}

	public String getShortCode() {
		return shortCode;
	}

	public MessageCountType getType() {
		return type;
	}

	public long getCount() {
		return count;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MessageCount)) {
			return false;
		}
		MessageCount that = (MessageCount) o;
		return mms == that.mms &&
				count == that.count &&
				Objects.equals(localDate, that.localDate) &&
				Objects.equals(partnerId, that.partnerId) &&
				Objects.equals(shortCode, that.shortCode) &&
				type == that.type;
	}

	@Override
	public int hashCode() {
		return Objects.hash(localDate, partnerId, mms, shortCode, type, count);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("MessageCount{");
		sb.append("localDate=").append(localDate);
		sb.append(", partnerId=").append(partnerId);
		sb.append(", mms=").append(mms);
		sb.append(", shortCode='").append(shortCode).append('\'');
		sb.append(", type=").append(type);
		sb.append(", count=").append(count);
		sb.append('}');
		return sb.toString();
	}
}
