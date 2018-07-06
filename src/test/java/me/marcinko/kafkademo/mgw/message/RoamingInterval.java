package me.marcinko.kafkademo.mgw.message;

import java.time.Instant;
import java.util.Objects;

public class RoamingInterval {
	private final boolean inRoaming;
	private final Instant timeFrom;
	private final Instant timeTo;

	public RoamingInterval(boolean inRoaming, Instant timeFrom, Instant timeTo) {
		this.inRoaming = inRoaming;
		this.timeFrom = timeFrom;
		this.timeTo = timeTo;
	}

	public boolean isInRoaming() {
		return inRoaming;
	}

	public Instant getTimeFrom() {
		return timeFrom;
	}

	public Instant getTimeTo() {
		return timeTo;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RoamingInterval)) {
			return false;
		}
		RoamingInterval that = (RoamingInterval) o;
		return inRoaming == that.inRoaming &&
				Objects.equals(timeFrom, that.timeFrom) &&
				Objects.equals(timeTo, that.timeTo);
	}

	@Override
	public int hashCode() {
		return Objects.hash(inRoaming, timeFrom, timeTo);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("RoamingInterval{");
		sb.append("inRoaming=").append(inRoaming);
		sb.append(", timeFrom=").append(timeFrom);
		sb.append(", timeTo=").append(timeTo);
		sb.append('}');
		return sb.toString();
	}
}
