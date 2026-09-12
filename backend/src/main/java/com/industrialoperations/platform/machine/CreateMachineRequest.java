package com.industrialoperations.platform.machine;

public record CreateMachineRequest(String name, String externalReference){

    public CreateMachineRequest{
        if(name == null || name.isBlank()){
            throw new IllegalArgumentException("Machine name cannot be blank");
        }
    }
    
}
