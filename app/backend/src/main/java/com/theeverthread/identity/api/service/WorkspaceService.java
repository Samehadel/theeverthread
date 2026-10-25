package com.theeverthread.identity.api.service;

import com.theeverthread.identity.api.dto.IdentityViews.AccountView;

public interface WorkspaceService {
    AccountView select(String email, Long id);

    AccountView create(String email, String name);

    AccountView rename(String email, Long id, String name);
}
