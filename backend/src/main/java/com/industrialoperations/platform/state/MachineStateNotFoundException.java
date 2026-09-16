package com.industrialoperations.platform.state;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class MachineStateNotFoundException extends RuntimeException {
    public MachineStateNotFoundException(String message) {
        super(message);
    }
}