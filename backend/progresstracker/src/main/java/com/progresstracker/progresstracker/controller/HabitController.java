package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import com.progresstracker.progresstracker.service.HabitProgressService;
import com.progresstracker.progresstracker.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;

@RestController
@RequestMapping("/api/habits")
@CrossOrigin(origins = "http://localhost:5173")
public class HabitController {

    private final HabitRepository habitRepository;
    private final UserRepository userRepository;
    private final HabitProgressService habitProgressService;
    private final HabitEntryRepository habitEntryRepository;

    public HabitController(HabitRepository habitRepository,
                           UserRepository userRepository,
                           HabitProgressService habitProgressService,
                           HabitEntryRepository habitEntryRepository) {
        this.habitRepository = habitRepository;
        this.userRepository = userRepository;
        this.habitProgressService = habitProgressService;
        this.habitEntryRepository = habitEntryRepository;
    }

    private User requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }

    @GetMapping
    public List<Habit> getAllHabits(Authentication authentication) {
        User user = requireUser(authentication);
        return habitRepository.findByUser(user);
    }

    @PostMapping
    public Habit createHabit(@RequestBody Habit habit, Authentication authentication) {
        User user = requireUser(authentication);
        habit.setUser(user);
        return habitRepository.save(habit);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id, Authentication authentication) {
        User user = requireUser(authentication);

        Habit habit = habitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

        if (!habit.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete someone else's habit");
        }

        habitEntryRepository.deleteByHabitId(id);
        habitRepository.delete(habit);
    }


    @PutMapping("/{id}")
    public Habit update(@PathVariable Long id,
                        @RequestBody Habit updated,
                        Authentication authentication) {
        User user = requireUser(authentication);

        return habitRepository.findById(id)
                .map(existing -> {
                    if (!existing.getUser().getId().equals(user.getId())) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot edit someone else's habit");
                    }
                    existing.setName(updated.getName());
                    existing.setDescription(updated.getDescription());
                    existing.setFrequency(updated.getFrequency());
                    return habitRepository.save(existing);
                })
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found")
                );
    }

    @PostMapping("/{id}/complete")
    public Habit completeHabit(@PathVariable Long id, Authentication authentication) {
        User user = requireUser(authentication);

        Habit habit = habitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

        if (habit.getUser() == null || !habit.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot complete someone else's habit");
        }

        return habitProgressService.completeToday(habit);
    }

}
