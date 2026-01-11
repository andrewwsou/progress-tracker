package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDate;

@Service
public class HabitProgressService {

    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;

    public HabitProgressService(HabitRepository habitRepository, HabitEntryRepository habitEntryRepository) {
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
    }

    @Transactional
    public Habit completeToday(Habit habit) {
        LocalDate today = LocalDate.now();

        if (habitEntryRepository.findByHabitAndCompletedDate(habit, today).isPresent()) {
            return habit;
        }

        LocalDate last = habit.getLastCompletedDate();
        int nextStreak;

        if (last == null) {
            nextStreak = 1;
        } else if (today.equals(last.plusDays(1))) {
            nextStreak = habit.getCurrentStreak() + 1;
        } else {
            nextStreak = 1;
        }

        habit.setCurrentStreak(nextStreak);
        habit.setLongestStreak(Math.max(habit.getLongestStreak(), nextStreak));
        habit.setLastCompletedDate(today);

        int xpEarned = 10 + Math.min(20, (nextStreak - 1) * 2);
        habit.setXpTotal(habit.getXpTotal() + xpEarned);

        habitEntryRepository.save(new HabitEntry(habit, today, xpEarned));
        return habitRepository.save(habit);
    }

}
