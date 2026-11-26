package com.dbdiff.consolidation.models.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RequestBodyDTO<T> {
    private String responseCode;
    private T requestParam;
    private T authParam;
}
