package com.unihub.backend.dto;

import java.time.LocalDate;

public record WorkshopFilters(String q, LocalDate from, LocalDate to, Fee fee, boolean seatsAvailable) {
    public enum Fee { FREE, PAID }

    public WorkshopFilters {
        q = q == null ? "" : q.strip();
        if (q.length() > 120) {
            throw new IllegalArgumentException("Search must be at most 120 characters");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Start date must be on or before end date");
        }
        if (outsideCalendarRange(from) || outsideCalendarRange(to)) {
            throw new IllegalArgumentException("Date must have a four-digit year");
        }
    }

    private static boolean outsideCalendarRange(LocalDate date) {
        return date != null && (date.getYear() < 1 || date.getYear() > 9999);
    }

    public boolean isEmpty() {
        return q.isEmpty() && from == null && to == null && fee == null && !seatsAvailable;
    }
}
