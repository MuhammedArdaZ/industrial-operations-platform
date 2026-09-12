package com.industrialoperations.platform.machine;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateExternalReferenceException extends RuntimeException{
    
    public DuplicateExternalReferenceException(String message){
        super(message);
    }

}
