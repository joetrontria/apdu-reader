package com.example.apdutool;

import android.text.Editable;
import android.text.TextWatcher;

/**
 * TextWatcher with only the one callback we care about, so the activity does
 * not have to implement three empty methods every time it watches a field.
 */
public abstract class SimpleTextWatcher implements TextWatcher
{
    /**
     * Called whenever the watched field's text changes.
     *
     * @param text the full current contents of the field
     */
    public abstract void onTextChanged(String text);

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after)
    {
        /* not needed */
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count)
    {
        /* not needed - we use afterTextChanged so the edit is complete */
    }

    @Override
    public void afterTextChanged(Editable s)
    {
        onTextChanged(s.toString());
    }
}
