-- Match the registration states that currently consume capacity.
DROP INDEX unique_user_workshop;
CREATE UNIQUE INDEX unique_user_workshop
    ON registrations (user_id, workshop_id)
    WHERE status IN ('PENDING', 'SUCCESS');

CREATE INDEX idx_registrations_workshop_occupied
    ON registrations (workshop_id, status)
    WHERE status IN ('PENDING', 'SUCCESS');

CREATE INDEX idx_registrations_pending_expiry
    ON registrations (created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_workshops_published_room_schedule
    ON workshops (room_id, start_time, end_time)
    WHERE status = 'PUBLISHED';

UPDATE workshops w
SET remaining_slots = GREATEST(0, w.total_slots - (
    SELECT count(*) FROM registrations r
    WHERE r.workshop_id = w.id AND r.status IN ('PENDING', 'SUCCESS')
));
