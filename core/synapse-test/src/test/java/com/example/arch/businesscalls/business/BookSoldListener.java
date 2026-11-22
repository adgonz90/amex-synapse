/*
 * Copyright 2026 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.example.arch.businesscalls.business;

import com.example.arch.businesscalls.model.event.BookSold;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * An event listener bean in the business layer.
 */
@Component
public class BookSoldListener {

    private long sold;

    @EventListener
    public void on(BookSold event) {
        sold += event.copies();
    }

    public long sold() {
        return sold;
    }
}
