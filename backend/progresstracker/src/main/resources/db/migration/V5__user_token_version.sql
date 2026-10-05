-- Each token carries the version its user had when it was issued. Signing out everywhere bumps the
-- version, and tokens with an older one are refused. Existing users start at 0, the version a
-- token issued before this migration is read as, so nobody is signed out by it.
alter table app_user add column token_version integer not null default 0;
