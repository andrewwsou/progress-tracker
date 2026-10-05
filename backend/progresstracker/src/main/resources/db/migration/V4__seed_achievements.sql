-- The achievements every user can unlock. Seeded here rather than by the services at startup, so
-- they exist before either service runs and several replicas starting at once cannot race to
-- insert them. Rows a service created before this migration are left as they are.
insert into achievement (code, name, description, threshold, type) values
    ('FIRST_COMPLETION', 'First Step', 'Complete a habit for the first time.', 1, 'COMPLETION'),
    ('STREAK_7', 'On a Roll', 'Reach a 7-day streak on any habit.', 7, 'STREAK'),
    ('XP_100', 'Level Up', 'Earn 100 total XP across all habits.', 100, 'XP')
on conflict (code) do nothing;
