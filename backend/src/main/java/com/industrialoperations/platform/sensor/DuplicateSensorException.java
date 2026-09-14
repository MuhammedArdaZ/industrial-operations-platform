package com.industrialoperations.platform.sensor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateSensorException extends RuntimeException{
    
    public DuplicateSensorException(String message){
        super(message);
    }

}
