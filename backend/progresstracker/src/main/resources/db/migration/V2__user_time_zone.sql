-- Each user's time zone (an IANA id such as America/Los_Angeles), so "today" for a completion,
-- a goal, or a streak is the user's own calendar day rather than the server's. Users who signed
-- up before this keep UTC, which is what the server used for them until now.
alter table app_user add column time_zone varchar(64) not null default 'UTC';
