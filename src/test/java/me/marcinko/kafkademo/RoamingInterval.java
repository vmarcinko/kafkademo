package me.marcinko.kafkademo;

import java.time.LocalDateTime;
import java.util.Objects;

public class RoamingInterval {
	private final boolean inRoaming;
	private final LocalDateTime intervalStart;
	private final LocalDateTime intervalEnd;

	public RoamingInterval(boolean inRoaming, LocalDateTime intervalStart, LocalDateTime intervalEnd) {
		this.inRoaming = inRoaming;
		this.intervalStart = intervalStart;
		this.intervalEnd = intervalEnd;
	}

	public boolean isInRoaming() {
		return inRoaming;
	}

	public boolean isWithin(LocalDateTime timestamp) {
		return timestamp.isBefore(this.intervalEnd) && !timestamp.isBefore(this.intervalStart);
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
				Objects.equals(intervalStart, that.intervalStart) &&
				Objects.equals(intervalEnd, that.intervalEnd);
	}

	@Override
	public int hashCode() {
		return Objects.hash(inRoaming, intervalStart, intervalEnd);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("RoamingInterval{");
		sb.append("inRoaming=").append(inRoaming);
		sb.append(", intervalStart=").append(intervalStart);
		sb.append(", intervalEnd=").append(intervalEnd);
		sb.append('}');
		return sb.toString();
	}
}
