package com.ureca.snac.asset.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.INVALID_ASSET_SOURCE;

public class InvalidAssetSourceException extends BusinessException {

    public InvalidAssetSourceException(String message) {
        super(INVALID_ASSET_SOURCE, message);
    }
}
