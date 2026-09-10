package com.gamestock.backend.environment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HanRiverTemperatureController {
    private final HanRiverTemperatureService temperatures;

    public HanRiverTemperatureController(HanRiverTemperatureService temperatures) {
        this.temperatures = temperatures;
    }

    @GetMapping("/han-river-temperature")
    public HanRiverTemperatureService.TemperatureReading current() {
        return temperatures.current();
    }
}
