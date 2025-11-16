package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import com.progresstracker.progresstracker.backend.model.User;

import java.util.List;

@RestController
@RequestMapping("/api/habits")
@CrossOrigin(origins = "http://localhost:5173")
public class HabitController {

    private final HabitRepository habitRepository;

    public HabitController(HabitRepository habitRepository) {
        this.habitRepository = habitRepository;
    }

    @GetMapping
    public List<Habit> getAllHabits(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return habitRepository.findByUser(user);
    }

    @PostMapping
    public Habit createHabit(@RequestBody Habit habit, Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        habit.setUser(user);
        return habitRepository.save(habit);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        if (!habitRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found");
        }
        habitRepository.deleteById(id);
    }

    @PutMapping("/{id}")
    public Habit update(@PathVariable Long id, @RequestBody Habit updated) {
        return habitRepository.findById(id)
                .map(existing -> {
                    existing.setName(updated.getName());
                    existing.setDescription(updated.getDescription());
                    existing.setFrequency(updated.getFrequency());
                    return habitRepository.save(existing);
                })
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found")
                );
    }
}
