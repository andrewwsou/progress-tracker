-- Emails are matched without regard to case: the API stores them in lower case, and this index
-- stops one mailbox holding two accounts under different capitalisations.
-- Two existing accounts that differ only in case cannot both keep their address, so they stop the
-- migration with a clear message instead of a constraint error; merge them by hand first.
do $$
begin
    if exists (select 1 from app_user group by lower(email) having count(*) > 1) then
        raise exception 'Some accounts share an email address in different case; merge them before migrating';
    end if;
end
$$;

update app_user set email = lower(email) where email <> lower(email);

create unique index uk_app_user_email_lower on app_user (lower(email));
