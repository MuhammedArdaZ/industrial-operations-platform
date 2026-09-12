package com.industrialoperations.platform.machine;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class MachineNotFoundException extends RuntimeException{
    
    public MachineNotFoundException(String message){
        super(message);
    }

}
