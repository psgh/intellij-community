package com.intellij.lexer;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.psi.jsp.el.ELTokenType;
import com.intellij.psi.jsp.JspSpiUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.text.CharArrayCharSequence;

public class HtmlHighlightingLexer extends BaseHtmlLexer {
  private static final Logger LOG = Logger.getInstance("#com.intellij.lexer.HtmlHighlightingLexer");

  private static final int EMBEDDED_LEXER_ON = 0x1 << BASE_STATE_SHIFT;
  private static final int EMBEDDED_LEXER_STATE_SHIFT = BASE_STATE_SHIFT + 1;

  private Lexer embeddedLexer;
  private Lexer styleLexer;
  private Lexer scriptLexer;
  private Lexer elLexer;
  private boolean hasNoEmbeddments;
  private static FileType ourStyleFileType;
  private static FileType ourScriptFileType;

  class XmlEmbeddmentHandler implements TokenHandler {
    public void handleElement(Lexer lexer) {
      if (!hasSeenStyle() && !hasSeenScript() || hasNoEmbeddments) return;
      final IElementType tokenType = lexer.getTokenType();

      if ((tokenType==XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN && hasSeenAttribute()) ||
          (tokenType==XmlTokenType.XML_DATA_CHARACTERS && hasSeenTag()) ||
          tokenType==XmlTokenType.XML_COMMENT_CHARACTERS && hasSeenTag()
          ) {
        setEmbeddedLexer();
        
        if (embeddedLexer!=null) {
          embeddedLexer.start(
            getBufferSequence(),
            HtmlHighlightingLexer.super.getTokenStart(),
            skipToTheEndOfTheEmbeddment(),
            embeddedLexer instanceof EmbedmentLexer ? ((EmbedmentLexer)embeddedLexer).getEmbeddedInitialState(tokenType) : 0
          );
          
          if (embeddedLexer.getTokenType() == null) {
            // no content for embeddment
            embeddedLexer = null;
          }
        }
      }
    }
  }

  class ElEmbeddmentHandler implements TokenHandler {
    public void handleElement(Lexer lexer) {
      setEmbeddedLexer();
      if (embeddedLexer != null) {
        embeddedLexer.start(getBufferSequence(),HtmlHighlightingLexer.super.getTokenStart(),HtmlHighlightingLexer.super.getTokenEnd(), 0);
      }
    }
  }

  public HtmlHighlightingLexer() {
    this(new MergingLexerAdapter(new FlexAdapter(new _HtmlLexer()),TOKENS_TO_MERGE),true);
  }

  protected HtmlHighlightingLexer(Lexer lexer, boolean caseInsensitive) {
    super(lexer,caseInsensitive);

    XmlEmbeddmentHandler value = new XmlEmbeddmentHandler();
    registerHandler(XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN,value);
    registerHandler(XmlTokenType.XML_DATA_CHARACTERS,value);
    registerHandler(XmlTokenType.XML_COMMENT_CHARACTERS,value);
  }

  public void start(char[] buffer, int startOffset, int endOffset, int initialState) {
    start(new CharArrayCharSequence(buffer),startOffset, endOffset, initialState);
  }

  public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
    super.start(buffer, startOffset, endOffset, initialState);

    if ((initialState & EMBEDDED_LEXER_ON)!=0) {
      int state = initialState >> EMBEDDED_LEXER_STATE_SHIFT;
      setEmbeddedLexer();
      LOG.assertTrue(embeddedLexer!=null);
      embeddedLexer.start(buffer,startOffset,skipToTheEndOfTheEmbeddment(),state);
    } else {
      embeddedLexer = null;
    }
  }

  private void setEmbeddedLexer() {
    Lexer newLexer = null;
    if (hasSeenStyle()) {
      if (styleLexer==null) {
        styleLexer = (ourStyleFileType!=null)? SyntaxHighlighter.PROVIDER.create(ourStyleFileType, null, null).getHighlightingLexer():null;
      }

      newLexer = styleLexer;
    } else if (hasSeenScript()) {
      if (scriptLexer==null) {
        scriptLexer = (ourScriptFileType!=null)? SyntaxHighlighter.PROVIDER.create(ourScriptFileType, null, null).getHighlightingLexer():null;
      }
      newLexer = scriptLexer;
    } else if (super.getTokenType() == ELTokenType.JSP_EL_CONTENT) {
      if (elLexer==null) elLexer = JspSpiUtil.createElLexer();
      newLexer = elLexer;
    }

    if (newLexer!=null) {
      embeddedLexer = newLexer;
    }
  }

  public void advance() {
    if (embeddedLexer!=null) {
      embeddedLexer.advance();
      if (embeddedLexer.getTokenType()==null) {
        embeddedLexer=null;
      }
    }

    if (embeddedLexer==null) {
      super.advance();
    }
  }

  protected boolean isValidAttributeValueTokenType(final IElementType tokenType) {
    return super.isValidAttributeValueTokenType(tokenType) ||
      tokenType == ELTokenType.JSP_EL_CONTENT;
  }

  public IElementType getTokenType() {
    if (embeddedLexer!=null) {
      return embeddedLexer.getTokenType();
    } else {
      IElementType tokenType = super.getTokenType();

      // TODO: fix no DOCTYPE highlighting
      if (tokenType == null) return tokenType;

      if (tokenType==XmlTokenType.XML_NAME) {
        // we need to convert single xml_name for tag name and attribute name into to separate
        // lex types for the highlighting!
        final int state = getState() & BASE_STATE_MASK;

        if (isHtmlTagState(state)) {
          tokenType = XmlTokenType.XML_TAG_NAME;
        }
      }
      else if (tokenType == XmlTokenType.XML_WHITE_SPACE || tokenType == XmlTokenType.XML_REAL_WHITE_SPACE) {
        if (hasSeenTag() && (hasSeenStyle() || hasSeenScript())) {
          tokenType = XmlTokenType.XML_WHITE_SPACE;
        } else {
          tokenType = (getState()!=0)?XmlTokenType.TAG_WHITE_SPACE:XmlTokenType.XML_REAL_WHITE_SPACE;
        }
      } else if (tokenType == XmlTokenType.XML_CHAR_ENTITY_REF ||
               tokenType == XmlTokenType.XML_ENTITY_REF_TOKEN
              ) {
        // we need to convert char entity ref & entity ref in comments as comment chars 
        final int state = getState() & BASE_STATE_MASK;
        if (state == _HtmlLexer.COMMENT) return XmlTokenType.XML_COMMENT_CHARACTERS;
      }
      return tokenType;
    }
  }

  public int getTokenStart() {
    if (embeddedLexer!=null) {
      return embeddedLexer.getTokenStart();
    } else {
      return super.getTokenStart();
    }
  }

  public int getTokenEnd() {
    if (embeddedLexer!=null) {
      return embeddedLexer.getTokenEnd();
    } else {
      return super.getTokenEnd();
    }
  }

  public static final void registerStyleFileType(FileType fileType) {
    ourStyleFileType = fileType;
  }

  public static void registerScriptFileType(FileType _scriptFileType) {
    ourScriptFileType = _scriptFileType;
  }

  public int getState() {
    int state = super.getState();

    state |= ((embeddedLexer!=null)?EMBEDDED_LEXER_ON:0);
    if (embeddedLexer!=null) state |= (embeddedLexer.getState() << EMBEDDED_LEXER_STATE_SHIFT);

    return state;
  }

  protected boolean isHtmlTagState(int state) {
    return state == _HtmlLexer.START_TAG_NAME || state == _HtmlLexer.END_TAG_NAME || 
           state  == _HtmlLexer.START_TAG_NAME2 || state == _HtmlLexer.END_TAG_NAME2;
  }

  public void setHasNoEmbeddments(boolean hasNoEmbeddments) {
    this.hasNoEmbeddments = hasNoEmbeddments;
  }
}