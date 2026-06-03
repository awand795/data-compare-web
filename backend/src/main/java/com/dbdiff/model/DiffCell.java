package com.dbdiff.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffCell {
    private Object sourceValue;
    private Object targetValue;
    
    @JsonProperty("isDifferent")
    private boolean isDifferent;
}
