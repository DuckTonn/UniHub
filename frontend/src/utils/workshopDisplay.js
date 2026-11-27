const dateLabel = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
const timeLabel = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' });
const monthLabel = new Intl.DateTimeFormat('en-GB', { month: 'short' });

export const formatWorkshopDate = (value) => {
    const date = value ? new Date(value) : null;
    return date && !Number.isNaN(date.getTime()) ? date : null;
};

export const formatDateTime = (value) => {
    const date = formatWorkshopDate(value);
    return date ? `${dateLabel.format(date)} · ${timeLabel.format(date)}` : '—';
};

export const formatWorkshopMonth = (date) => monthLabel.format(date);

export const isRegistrationClosed = (workshop) => {
    const end = formatWorkshopDate(workshop.registrationEndTime || workshop.endTime);
    return end !== null && Date.now() > end.getTime();
};

export const isRegistrationStarted = (workshop) => {
    const start = formatWorkshopDate(workshop.registrationStartTime);
    return start !== null && Date.now() >= start.getTime();
};

export const formatPrice = (price) => Number(price)
    ? `${Number(price).toLocaleString('vi-VN')} ₫`
    : 'Free';

export const formatDescription = (description) => typeof description === 'string' && description.trim()
    ? description.trim()
    : 'This workshop does not have a description yet.';
