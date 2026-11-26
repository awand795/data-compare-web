package com.dbdiff.consolidation.controllers;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dbdiff.consolidation.models.dtos.RequestBodyDTO;
import com.dbdiff.consolidation.services.KonsolidasiServices;
import com.dbdiff.consolidation.services.SystemManagementServices;
import com.dbdiff.consolidation.utils.ObjectParser;
import com.dbdiff.consolidation.utils.ResponseData;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping({ "/api/konsolidasi", "/konsolidasi/api" })
public class ConsolidationController {

    @Autowired
    private SystemManagementServices clsSYSM;

    @Autowired
    private KonsolidasiServices clsKNSL;

    @PostMapping("")
    public Object getData(@RequestBody RequestBodyDTO<?> reqBody) {
        String resCode = reqBody.getResponseCode() != null ? reqBody.getResponseCode() : "";
        JSONObject reqP = reqBody.getRequestParam() == null
                ? new JSONObject()
                : reqBody.getRequestParam() instanceof String
                        ? new JSONObject(reqBody.getRequestParam().toString())
                        : ObjectParser.toJSONObject(reqBody.getRequestParam());
        JSONObject authP = reqBody.getAuthParam() == null
                ? new JSONObject()
                : reqBody.getAuthParam() instanceof String
                        ? new JSONObject(reqBody.getAuthParam().toString())
                        : ObjectParser.toJSONObject(reqBody.getAuthParam());

        switch (resCode) {
            case "GET-DATA-PERUSAHAAN":
                return clsSYSM.getDataPerusahaan(reqP, authP);
            case "GET-DATA-LANGGANAN-KONSOLIDASI":
                return clsKNSL.getDataLanggananKonsolidasi(reqP, authP);
            case "GET-DATA-LANGGANAN-ERP":
                reqP.put("api_method", "custom-api");
                return clsKNSL.getDataLanggananERP(reqP, authP);
            case "GET-DATA-LANGGANAN-PERUSAHAAN":
                return clsKNSL.getDataLanggananPerusahaan(reqP, authP);
            case "SET-DATA-LANGGANAN-KONSOLIDASI":
                reqP.put("api_method", "standart-api");
                return clsKNSL.setDataLanggananERPNew(reqP, authP);
            case "UPT-DATA-LANGGANAN-KONSOLIDASI":
                reqP.put("api_method", "standart-api");
                return clsKNSL.uptDataLanggananERPMatch(reqP, authP);
            case "UPT-DATA-LANGGANAN-KOREKSI":
                return clsKNSL.uptDataLanggananERPKoreksi(reqP, authP);
            default:
                return ResponseData.buildResponse(404, "N", "UNKNOWN RESPONSE CODE: " + resCode);
        }
    }
}
