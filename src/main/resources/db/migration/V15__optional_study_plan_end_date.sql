-- Plans may omit an end date; keep existing dates and align storage with the API.
ALTER TABLE study_plans MODIFY COLUMN end_date DATE NULL;
