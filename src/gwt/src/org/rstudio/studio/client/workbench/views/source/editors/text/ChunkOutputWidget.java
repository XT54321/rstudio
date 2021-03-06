/*
 * ChunkOutputWidget.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.source.editors.text;

import org.rstudio.core.client.ColorUtil;
import org.rstudio.core.client.VirtualConsole;
import org.rstudio.core.client.dom.DomUtils;
import org.rstudio.core.client.dom.ImageElementEx;
import org.rstudio.core.client.js.JsArrayEx;
import org.rstudio.core.client.widget.FixedRatioWidget;
import org.rstudio.core.client.widget.PreWidget;
import org.rstudio.core.client.widget.ProgressSpinner;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.application.events.InterruptStatusEvent;
import org.rstudio.studio.client.application.events.RestartStatusEvent;
import org.rstudio.studio.client.common.Value;
import org.rstudio.studio.client.common.debugging.model.UnhandledError;
import org.rstudio.studio.client.common.debugging.ui.ConsoleError;
import org.rstudio.studio.client.rmarkdown.model.RmdChunkOptions;
import org.rstudio.studio.client.rmarkdown.model.RmdChunkOutput;
import org.rstudio.studio.client.rmarkdown.model.RmdChunkOutputUnit;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptHandler;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteErrorEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteErrorHandler;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteOutputEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteOutputHandler;
import org.rstudio.studio.client.workbench.views.source.editors.text.rmd.ChunkOutputHost;
import org.rstudio.studio.client.workbench.views.source.editors.text.rmd.ChunkOutputUi;
import org.rstudio.studio.client.workbench.views.source.editors.text.rmd.TextEditingTargetNotebook;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Display;
import com.google.gwt.dom.client.Style.Overflow;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.EventListener;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

public class ChunkOutputWidget extends Composite
                               implements ConsoleWriteOutputHandler,
                                          ConsoleWriteErrorHandler,
                                          ConsolePromptHandler,
                                          RestartStatusEvent.Handler,
                                          InterruptStatusEvent.Handler,
                                          ConsoleError.Observer
{

   private static ChunkOutputWidgetUiBinder uiBinder = GWT
         .create(ChunkOutputWidgetUiBinder.class);

   interface ChunkOutputWidgetUiBinder
         extends UiBinder<Widget, ChunkOutputWidget>
   {
      
   }
   
   public interface Resources extends ClientBundle
   {
      @Source("ExpandChunkIcon.png")
      ImageResource expandChunkIcon();
      
      @Source("CollapseChunkIcon.png")
      ImageResource collapseChunkIcon();

      @Source("RemoveChunkIcon.png")
      ImageResource removeChunkIcon();
   }
   
   public interface ChunkStyle extends CssResource
   {
      String overflowY();
      String collapsed();
      String spinner();
   }

   public ChunkOutputWidget(String chunkId, RmdChunkOptions options, 
         int expansionState, ChunkOutputHost host)
   {
      chunkId_ = chunkId;
      host_ = host;
      queuedError_ = "";
      options_ = options;
      initWidget(uiBinder.createAndBindUi(this));
      expansionState_ = new Value<Integer>(expansionState);
      applyCachedEditorStyle();
      if (expansionState_.getValue() == COLLAPSED)
         setCollapsedStyles();
      
      frame_.getElement().getStyle().setHeight(
            expansionState_.getValue() == COLLAPSED ? 
                  ChunkOutputUi.CHUNK_COLLAPSED_HEIGHT :
                  ChunkOutputUi.MIN_CHUNK_HEIGHT, Unit.PX);
      
      DOM.sinkEvents(clear_.getElement(), Event.ONCLICK);
      DOM.setEventListener(clear_.getElement(), new EventListener()
      {
         @Override
         public void onBrowserEvent(Event evt)
         {
            switch(DOM.eventGetType(evt))
            {
            case Event.ONCLICK:
               destroyConsole();
               host_.onOutputRemoved();
               break;
            };
         }
      });
      
      EventListener toggleExpansion = new EventListener()
      {
         @Override
         public void onBrowserEvent(Event evt)
         {
            switch(DOM.eventGetType(evt))
            {
            case Event.ONCLICK:
               toggleExpansionState();
               break;
            };
         }
      };

      DOM.sinkEvents(expander_.getElement(), Event.ONCLICK);
      DOM.setEventListener(expander_.getElement(), toggleExpansion);
      
      DOM.sinkEvents(expand_.getElement(), Event.ONCLICK);
      DOM.setEventListener(expand_.getElement(), toggleExpansion);
      
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.addHandler(RestartStatusEvent.TYPE, this);
      events.addHandler(InterruptStatusEvent.TYPE, this);
   }
   
   // Public methods ----------------------------------------------------------
   
   public void showChunkOutputUnit(RmdChunkOutputUnit unit, int mode,
         boolean ensureVisible)
   {
      // no-op for empty console objects (avoid initializing output when we have
      // nothing to show)
      if (unit.getType() == RmdChunkOutputUnit.TYPE_TEXT && 
          unit.getArray().length() < 1)
         return;
      
      initializeOutput(unit.getType());
      switch(unit.getType())
      {
      case RmdChunkOutputUnit.TYPE_TEXT:
         showConsoleOutput(unit.getArray());
         break;
      case RmdChunkOutputUnit.TYPE_HTML:
         showHtmlOutput(unit.getString(), ensureVisible);
         break;
      case RmdChunkOutputUnit.TYPE_PLOT:
         showPlotOutput(unit.getString(), ensureVisible);
         break;
      case RmdChunkOutputUnit.TYPE_ERROR:
         // override visibility flag when there's an error in batch mode
         if (!options_.error() && mode == TextEditingTargetNotebook.MODE_BATCH)
            ensureVisible = true;
         showErrorOutput(unit.getUnhandledError(), ensureVisible);
         break;
      }
   }
   
   public int getExpansionState()
   {
      return expansionState_.getValue();
   }
   
   public void setExpansionState(int state)
   {
      if (state == expansionState_.getValue())
         return;
      else
         toggleExpansionState();
   }
   
   public int getState()
   {
      return state_;
   }

   public void setOptions(RmdChunkOptions options)
   {
      boolean needsSync = options_.include() != options.include();
      options_ = options;
      
      if (needsSync)
         syncHeight(false, false);
   }
   
   public HandlerRegistration addExpansionStateChangeHandler(
         ValueChangeHandler<Integer> handler)
   {
      return expansionState_.addValueChangeHandler(handler);
   }
    
   public void showChunkOutput(RmdChunkOutput output, int mode, 
         boolean ensureVisible)
   {
      if (output.getType() == RmdChunkOutput.TYPE_MULTIPLE_UNIT)
      {
         JsArray<RmdChunkOutputUnit> units = output.getUnits();
      
         // prepare chunk for output on replay
         if (output.isReplay() && state_ == CHUNK_EMPTY && units.length() > 0)
            state_ = CHUNK_PRE_OUTPUT;

         // loop over the output units and emit the appropriate contents for
         // each
         for (int i = 0; i < units.length(); i++)
         {
            showChunkOutputUnit(units.get(i), mode, ensureVisible);
         }

         // ensure the output is visible if requested
         onOutputFinished(ensureVisible);
      }
      else if (output.getType() == RmdChunkOutput.TYPE_SINGLE_UNIT)
      {
         showChunkOutputUnit(output.getUnit(), mode, ensureVisible);
      }
   }
   
   public void syncHeight(boolean scrollToBottom, boolean ensureVisible)
   {
      // special behavior for chunks which don't have output included by 
      // default
      if (!options_.include() && !hasErrors_)
      {
         if (isVisible())
         {
            setVisible(false);
            host_.onOutputHeightChanged(0, ensureVisible);
         }
         return;
      }
      
      setVisible(true);
      
      // clamp chunk height to min/max (the +19 is the sum of the vertical
      // padding on the element)
      int height = ChunkOutputUi.CHUNK_COLLAPSED_HEIGHT;
      if (expansionState_.getValue() == EXPANDED)
      {
         int contentHeight = root_.getElement().getScrollHeight() + 19;
         height = Math.min(
               Math.max(ChunkOutputUi.MIN_CHUNK_HEIGHT, contentHeight), 
               ChunkOutputUi.MAX_CHUNK_HEIGHT);

         // if we have renders pending, don't shrink until they're loaded 
         if (pendingRenders_ > 0 && height < renderedHeight_)
            return;
      }

      // don't report height if it hasn't changed
      if (height == renderedHeight_)
         return;

      // cache last reported render size
      renderedHeight_ = height;
      if (scrollToBottom)
         root_.getElement().setScrollTop(root_.getElement().getScrollHeight());
      frame_.getElement().getStyle().setHeight(height, Unit.PX);
         
      // allocate some extra space so the cursor doesn't touch the output frame
      host_.onOutputHeightChanged(height + 7, ensureVisible);
   }
   
   public static boolean isEditorStyleCached()
   {
      return s_backgroundColor != null &&
             s_color != null &&
             s_outlineColor != null;
   }
   
   public void onOutputFinished(boolean ensureVisible)
   {
      // flush any remaining queued errors
      flushQueuedErrors(ensureVisible);
      
      if (state_ == CHUNK_PRE_OUTPUT)
      {
         // if no output was produced, clear the contents and show the empty
         // indicator
         emptyIndicator_.setVisible(true);
         if (vconsole_ != null)
            vconsole_.clear();
         root_.clear();
         hasErrors_ = false;
      }
      syncHeight(true, ensureVisible);
      state_ = CHUNK_READY;
      lastOutputType_ = RmdChunkOutputUnit.TYPE_NONE;
      setOverflowStyle();
      showReadyState();
   }

   public void setCodeExecuting(boolean entireChunk, int mode)
   {
      // expand if currently collapsed
      if (expansionState_.getValue() == COLLAPSED)
         toggleExpansionState();

      // do nothing if code is already executing
      if (state_ == CHUNK_PRE_OUTPUT || 
          state_ == CHUNK_POST_OUTPUT)
      {
         return;
      }

      registerConsoleEvents();
      state_ = CHUNK_PRE_OUTPUT;
      execMode_ = mode;
      showBusyState();
   }
   
   public static void cacheEditorStyle(Element editorContainer, 
         Style editorStyle)
   {
      s_backgroundColor = editorStyle.getBackgroundColor();
      s_color = editorStyle.getColor();
      
      // use a muted version of the text color for the outline
      ColorUtil.RGBColor text = ColorUtil.RGBColor.fromCss(
            DomUtils.extractCssValue("ace_editor", "color"));
      
      // dark themes require a slightly more pronounced color
      ColorUtil.RGBColor outline = new ColorUtil.RGBColor(
            text.red(), text.green(), text.blue(),
            text.isDark() ? 0.12: 0.18);

      s_outlineColor = outline.asRgb();
   }
   
   public void showServerError(ServerError error)
   {
      // consider: less obtrusive error message 
      RStudioGinjector.INSTANCE.getGlobalDisplay().showErrorMessage(
            "Chunk Execution Error", error.getMessage());
      
      // treat as an interrupt (don't clear output)
      completeInterrupt();
   }
   
   public void applyCachedEditorStyle()
   {
      if (!isEditorStyleCached())
         return;
      Style frameStyle = frame_.getElement().getStyle();
      frameStyle.setBorderColor(s_outlineColor);
      emptyIndicator_.getElement().getStyle().setColor(s_color);

      // apply the style to any frames in the output
      for (Widget w: root_)
      {
         if (w instanceof ChunkOutputFrame)
         {
            ChunkOutputFrame frame = (ChunkOutputFrame)w;
            Style bodyStyle = frame.getDocument().getBody().getStyle();
            bodyStyle.setColor(s_color);
         }
      }
      getElement().getStyle().setBackgroundColor(s_backgroundColor);
      frame_.getElement().getStyle().setBackgroundColor(s_backgroundColor);
   }
   
   public boolean hasErrors()
   {
      return hasErrors_;
   }
   
   // Event handlers ----------------------------------------------------------

   @Override
   public void onConsoleWriteOutput(ConsoleWriteOutputEvent event)
   {
      if (event.getConsole() != chunkId_)
         return;

      // flush any queued errors 
      flushQueuedErrors(false);

      renderConsoleOutput(event.getOutput(), classOfOutput(CONSOLE_OUTPUT),
            execMode_ == TextEditingTargetNotebook.MODE_SINGLE);
   }
   
   @Override
   public void onConsoleWriteError(ConsoleWriteErrorEvent event)
   {
      if (event.getConsole() != chunkId_)
         return;
      
      // queue the error -- we don't emit errors right away since a more 
      // detailed error event may be forthcoming
      queuedError_ += event.getError();
   }
   
   @Override
   public void onConsolePrompt(ConsolePromptEvent event)
   {
      // stop listening to the console once the prompt appears, but don't 
      // clear up output until we receive the output finished event
      unregisterConsoleEvents();
   }
   
   @Override
   public void onRestartStatus(RestartStatusEvent event)
   {
      if (event.getStatus() != RestartStatusEvent.RESTART_COMPLETED)
         return;
      
      // when R is restarted, we're not going to get any more output, so act
      // as though the server told us it's done
      if (state_ != CHUNK_READY)
      {
         onOutputFinished(false);
      }
   }

   @Override
   public void onInterruptStatus(InterruptStatusEvent event)
   {
      if (event.getStatus() != InterruptStatusEvent.INTERRUPT_COMPLETED)
         return;
      
      completeInterrupt();
   }

   @Override
   public void onErrorBoxResize()
   {
      syncHeight(true, true);
   }

   @Override
   public void runCommandWithDebug(String command)
   {
      // Not implemented
   }

   // Private methods ---------------------------------------------------------

   private String classOfOutput(int type)
   {
      if (type == CONSOLE_ERROR)
        return RStudioGinjector.INSTANCE.getUIPrefs().getThemeErrorClass();
      else if (type == CONSOLE_INPUT)
        return "ace_keyword";
      return null;
   }
   
   private void showConsoleOutput(JsArray<JsArrayEx> output)
   {
      for (int i = 0; i < output.length(); i++)
      {
         // the first element is the output, and the second is the text; if we
         // don't have at least 2 elements, it's not a valid entry
         if (output.get(i).length() < 2)
            continue;

         int outputType = output.get(i).getInt(0);
         String outputText = output.get(i).getString(1);
         
         // we don't currently render input as output
         if (outputType == CONSOLE_INPUT)
            continue;
         
         if (outputType == CONSOLE_ERROR)
         {
            queuedError_ += outputText;
         }
         else
         {
            // release any queued errors
            if (!queuedError_.isEmpty())
            {
               vconsole_.submit(queuedError_, classOfOutput(CONSOLE_ERROR));
               queuedError_ = "";
            }

            vconsole_.submit(outputText, classOfOutput(outputType));
         }
      }
      vconsole_.redraw(console_.getElement());
      setOverflowStyle();
   }
   
   private void completeUnitRender(boolean ensureVisible)
   {
      syncHeight(true, ensureVisible);
   }
   
   private void showErrorOutput(UnhandledError err, final boolean ensureVisible)
   {
      hasErrors_ = true;
      
      // if there's only one error frame, it's not worth showing dedicated 
      // error UX
      if (err.getErrorFrames() != null &&
          err.getErrorFrames().length() < 2)
      {
         flushQueuedErrors(ensureVisible);
         return;
      }

      int idx = queuedError_.indexOf(err.getErrorMessage());
      if (idx >= 0)
      {
         // emit any messages queued prior to the error
         if (idx > 0)
         {
            renderConsoleOutput(queuedError_.substring(0, idx), 
                  classOfOutput(CONSOLE_ERROR),
                  ensureVisible);
            initializeOutput(RmdChunkOutputUnit.TYPE_ERROR);
         }
         // leave messages following the error in the queue
         queuedError_ = queuedError_.substring(
               idx + err.getErrorMessage().length());
      }
      else
      {
         // flush any irrelevant messages from the stream
         flushQueuedErrors(ensureVisible);
      }
      
      UIPrefs prefs =  RStudioGinjector.INSTANCE.getUIPrefs();
      ConsoleError error = new ConsoleError(err, prefs.getThemeErrorClass(), 
            this, null);
      error.setTracebackVisible(prefs.autoExpandErrorTracebacks().getValue());

      root_.add(error);
      flushQueuedErrors(ensureVisible);
      completeUnitRender(ensureVisible);
   }
   
   private void showPlotOutput(String url, final boolean ensureVisible)
   {
      // flush any queued errors
      flushQueuedErrors(ensureVisible);

      final Image plot = new Image();
      
      // set to auto height and hidden -- we need the image to load so we can 
      // compute its natural width before showing it
      plot.getElement().getStyle().setProperty("height", "auto");
      plot.getElement().getStyle().setDisplay(Display.NONE);

      root_.add(plot);
      final Timer renderTimeout = new RenderTimer();
      
      DOM.sinkEvents(plot.getElement(), Event.ONLOAD);
      DOM.setEventListener(plot.getElement(), new EventListener()
      {
         @Override
         public void onBrowserEvent(Event event)
         {
            if (DOM.eventGetType(event) != Event.ONLOAD)
               return;
            
            ImageElementEx img = plot.getElement().cast();

            // grow the image to fill the container, but not beyond its
            // natural width. also clamp image width to avoid overly-large
            // images in a wide editor buffer
            int maxWidth = Math.min(624, img.naturalWidth());
            img.getStyle().setWidth(100, Unit.PCT);
            img.getStyle().setProperty("maxWidth", maxWidth + "px");

            // show the image
            plot.getElement().getStyle().setDisplay(Display.BLOCK);

            renderTimeout.cancel();
            completeUnitRender(ensureVisible);
         }
      });

      plot.setUrl(url);
   }
   
   private class RenderTimer extends Timer
   {
      public RenderTimer()
      {
         pendingRenders_++;

         // ensure we decrement the counter eventually even if content never
         // renders
         schedule(15000);
      }

      @Override
      public void cancel()
      {
         if (isRunning())
            pendingRenders_--;
         super.cancel();
      }

      @Override
      public void run()
      {
         pendingRenders_--;
      }
   };
   
   private void showHtmlOutput(String url, final boolean ensureVisible)
   {
      // flush any queued errors
      flushQueuedErrors(ensureVisible);
      
      // amend the URL to cause any contained widget to use the RStudio viewer
      // sizing policy
      if (url.indexOf('?') > 0)
         url += "&";
      else
         url += "?";
      url += "viewer_pane=1";

      ChunkOutputFrame frame = new ChunkOutputFrame();
      final FixedRatioWidget<ChunkOutputFrame> fixedFrame = 
            new FixedRatioWidget<ChunkOutputFrame>(frame, 1.618, 650);

      root_.add(fixedFrame);

      final Timer renderTimeout = new RenderTimer();

      frame.loadUrl(url, new Command() 
      {
         @Override
         public void execute()
         {
            Element body = fixedFrame.getWidget().getDocument().getBody();
            Style bodyStyle = body.getStyle();
            
            bodyStyle.setPadding(0, Unit.PX);
            bodyStyle.setMargin(0, Unit.PX);
            bodyStyle.setColor(s_color);
            
            renderTimeout.cancel();
            completeUnitRender(ensureVisible);
         };
      });
   }
   
   private void renderConsoleOutput(String text, String clazz, 
         boolean ensureVisible)
   {
      initializeOutput(RmdChunkOutputUnit.TYPE_TEXT);
      vconsole_.submitAndRender(text, clazz,
            console_.getElement());
      syncHeight(true, ensureVisible);
   }
   
   private void initializeOutput(int outputType)
   {
      if (state_ == CHUNK_PRE_OUTPUT)
      {
         // if no output has been emitted yet, clean up all existing output
         if (vconsole_ != null)
            vconsole_.clear();
         root_.clear();
         hasErrors_ = false;
         emptyIndicator_.setVisible(false);
         lastOutputType_ = RmdChunkOutputUnit.TYPE_NONE;
         state_ = CHUNK_POST_OUTPUT;
      }
      if (state_ == CHUNK_POST_OUTPUT)
      {
         if (lastOutputType_ == outputType)
            return;
         if (outputType == RmdChunkOutputUnit.TYPE_TEXT)
         {
            // if switching to textual output, allocate a new virtual console
            initConsole();
         }
         else if (lastOutputType_ == RmdChunkOutputUnit.TYPE_TEXT)
         {
            // if switching from textual input, clear the text accumulator
            if (vconsole_ != null)
               vconsole_.clear();
            console_ = null;
         }
      }
      lastOutputType_ = outputType;
   }

   private void initConsole()
   {
      if (vconsole_ == null)
         vconsole_ = new VirtualConsole();
      else
         vconsole_.clear();
      if (console_ == null)
      {
         console_ = new PreWidget();
         console_.getElement().removeAttribute("tabIndex");
         console_.getElement().getStyle().setMarginTop(0, Unit.PX);
         console_.getElement().getStyle().setProperty("whiteSpace", "pre-wrap");
      }
      else
      {
         console_.getElement().setInnerHTML("");
      }

      // attach the console
      root_.add(console_);
   }
   
   private void destroyConsole()
   {
      if (vconsole_ != null)
         vconsole_.clear();
      if (console_ != null)
         console_.removeFromParent();
      console_ = null;
   }
   
   private void registerConsoleEvents()
   {
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.addHandler(ConsoleWriteOutputEvent.TYPE, this);
      events.addHandler(ConsoleWriteErrorEvent.TYPE, this);
      events.addHandler(ConsolePromptEvent.TYPE, this);
   }

   private void unregisterConsoleEvents()
   {
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.removeHandler(ConsoleWriteOutputEvent.TYPE, this);
      events.removeHandler(ConsoleWriteErrorEvent.TYPE, this);
      events.removeHandler(ConsolePromptEvent.TYPE, this);
   }
   
   private void showBusyState()
   {
      if (spinner_ != null)
      {
         spinner_.removeFromParent();
         spinner_.detach();
         spinner_ = null;
      }
      // create a black or white spinner as appropriate
      ColorUtil.RGBColor bgColor = 
            ColorUtil.RGBColor.fromCss(s_backgroundColor);
      spinner_ = new ProgressSpinner(
            bgColor.isDark() ? ProgressSpinner.COLOR_WHITE :
                               ProgressSpinner.COLOR_BLACK);

      spinner_.getElement().addClassName(style.spinner());
      frame_.add(spinner_);
      spinner_.getElement().getStyle().setOpacity(1);
      root_.getElement().getStyle().setOpacity(0.2);

      clear_.setVisible(false);
      expand_.setVisible(false);
   }

   private void showReadyState()
   {
      getElement().getStyle().setBackgroundColor(s_backgroundColor);
      if (spinner_ != null)
      {
         spinner_.removeFromParent();
         spinner_.detach();
         spinner_ = null;
      }

      if (expansionState_.getValue() == EXPANDED)
         root_.getElement().getStyle().setOpacity(1);

      clear_.setVisible(true);
      expand_.setVisible(true);
   }
   
   private void setOverflowStyle()
   {
      Element ele = root_.getElement();
      boolean hasOverflow = ele.getScrollHeight() > ele.getOffsetHeight();
      if (hasOverflow && !root_.getElement().hasClassName(style.overflowY()))
      {
         frame_.getElement().addClassName(style.overflowY());
      }
      else if (!hasOverflow && 
               root_.getElement().hasClassName(style.overflowY()))
      {
         frame_.getElement().removeClassName(style.overflowY());
      }
   }
   
   private void completeInterrupt()
   {
      if (state_ == CHUNK_PRE_OUTPUT ||
          state_ == CHUNK_POST_OUTPUT)
      {
         state_ = CHUNK_READY;
      }
      else
      {
         return;
      }
      showReadyState();
   }
   
   private void toggleExpansionState()
   {
      // cancel any previously running expand/collapse timer
      if (collapseTimer_ != null && collapseTimer_.isRunning())
          collapseTimer_.cancel();

      if (expansionState_.getValue() == EXPANDED)
      {
         // remove scrollbars
         frame_.getElement().getStyle().setProperty("transition", 
               "height " + ANIMATION_DUR + "ms ease");
         setCollapsedStyles();
         collapseTimer_ = new Timer()
         {
            @Override
            public void run()
            {
               renderedHeight_ = 
                     ChunkOutputUi.CHUNK_COLLAPSED_HEIGHT;
               host_.onOutputHeightChanged(renderedHeight_, true);
            }
            
         };
         expansionState_.setValue(COLLAPSED, true);
      }
      else
      {
         clearCollapsedStyles();
         expansionState_.setValue(EXPANDED, true);
         syncHeight(true, true);
         collapseTimer_ = new Timer()
         {
            @Override
            public void run()
            {
               frame_.getElement().getStyle().clearProperty("transition");
            }
         };
      }
      collapseTimer_.schedule(ANIMATION_DUR);
   }
   
   private void setCollapsedStyles()
   {
      getElement().addClassName(style.collapsed());
      root_.getElement().getStyle().setOverflow(Overflow.HIDDEN);
      root_.getElement().getStyle().setOpacity(0);
      frame_.getElement().getStyle().setHeight(
            ChunkOutputUi.CHUNK_COLLAPSED_HEIGHT, Unit.PX);
   }
   
   private void clearCollapsedStyles()
   {
      getElement().removeClassName(style.collapsed());
      root_.getElement().getStyle().clearOverflow();
      root_.getElement().getStyle().clearOpacity();
   }
   
   private void flushQueuedErrors(boolean ensureVisible)
   {
      if (!queuedError_.isEmpty())
      {
         initializeOutput(RmdChunkOutputUnit.TYPE_TEXT);
         renderConsoleOutput(queuedError_, classOfOutput(CONSOLE_ERROR), 
               ensureVisible);
         queuedError_ = "";
      }
   }
   
   @UiField Image clear_;
   @UiField Image expand_;
   @UiField Label emptyIndicator_;
   @UiField HTMLPanel root_;
   @UiField ChunkStyle style;
   @UiField HTMLPanel frame_;
   @UiField HTMLPanel expander_;
   
   private PreWidget console_;
   private VirtualConsole vconsole_;
   private ProgressSpinner spinner_;
   private String queuedError_;
   private RmdChunkOptions options_;
   
   private int state_ = CHUNK_EMPTY;
   private int execMode_ = TextEditingTargetNotebook.MODE_SINGLE;
   private int lastOutputType_ = RmdChunkOutputUnit.TYPE_NONE;
   private int renderedHeight_ = 0;
   private int pendingRenders_ = 0;
   private boolean hasErrors_ = false;
   
   private Timer collapseTimer_ = null;
   private final String chunkId_;
   private final Value<Integer> expansionState_;
   private final ChunkOutputHost host_;

   private static String s_outlineColor    = null;
   private static String s_backgroundColor = null;
   private static String s_color           = null;
   
   public final static int EXPANDED   = 0;
   public final static int COLLAPSED  = 1;

   private final static int ANIMATION_DUR = 400;
   
   public final static int CHUNK_EMPTY       = 1;
   public final static int CHUNK_READY       = 2;
   public final static int CHUNK_PRE_OUTPUT  = 3;
   public final static int CHUNK_POST_OUTPUT = 4;
   
   public final static int CONSOLE_INPUT  = 0;
   public final static int CONSOLE_OUTPUT = 1;
   public final static int CONSOLE_ERROR  = 2;
}
