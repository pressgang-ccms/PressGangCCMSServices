package com.redhat.topicindex.syntaxchecker.data;

import java.util.List;

/**
 * This class represents a spelling error in a topic's XML
 */
public class SpellingErrorData implements Comparable<SpellingErrorData>
{
	/** The word that was misspelled */
	private final String misspelledWord;
	/** A list of spelling suggestions */
	private final List<String> suggestions;
	/** The number of times this word was misspelled in the topic */
	private int mispellCount = 1;

	public String getMisspelledWord()
	{
		return misspelledWord;
	}

	public List<String> getSuggestions()
	{
		return suggestions;
	}

	public int getMispellCount()
	{
		return mispellCount;
	}
	
	public SpellingErrorData(final String misspelledWord, final List<String> suggestions)
	{
		this.misspelledWord = misspelledWord;
		this.suggestions = suggestions;
	}
	
	public void incMispellCount()
	{
		++ this.mispellCount;
	}
	
	@Override
	public boolean equals(final Object other)
	{
		if (other == null)
			return false;
		
		if (!(other instanceof SpellingErrorData))
			return false;
		
		final SpellingErrorData otherSpellingErrorData = (SpellingErrorData)other;
		
		if (this.misspelledWord == null && otherSpellingErrorData.misspelledWord == null)
			return true;
		
		if  (this.misspelledWord == null || otherSpellingErrorData.misspelledWord == null)
			return false;
		
		return this.misspelledWord.equals(otherSpellingErrorData.misspelledWord);
	}

	public int compareTo(final SpellingErrorData other)
	{
		if (other == null)
			return 1;
		
		if (this.misspelledWord == null && other.misspelledWord == null)
			return 0;
		
		if  (this.misspelledWord == null )
			return -1;
		
		if (other.misspelledWord == null)
			return 1;
		
		return this.misspelledWord.compareTo(other.misspelledWord);
	}
}
