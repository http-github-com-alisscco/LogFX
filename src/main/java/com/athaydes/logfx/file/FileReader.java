package com.athaydes.logfx.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static com.athaydes.logfx.file.FileReader.LoadMode.MOVE;
import static com.athaydes.logfx.file.FileReader.LoadMode.REFRESH;

/**
 * Standard implementation of {@link FileContentReader}.
 */
public class FileReader implements FileContentReader {

    private static final Logger log = LoggerFactory.getLogger( FileReader.class );

    enum LoadMode {
        MOVE, REFRESH
    }

    private final File file;
    private final int fileWindowSize;
    private final int bufferSize;
    private final FileLineStarts lineStarts;

    public FileReader( File file, int fileWindowSize ) {
        this( file, fileWindowSize, 4096 );
    }

    FileReader( File file, int fileWindowSize, int bufferSize ) {
        this.file = file;
        this.fileWindowSize = fileWindowSize;
        this.bufferSize = bufferSize;

        // 1 extra line is needed because we need to know the boundaries between lines
        this.lineStarts = new FileLineStarts( fileWindowSize + 1 );
    }

    @Override
    public Optional<? extends List<String>> moveUp( int lines ) {
        return loadFromBottom( lineStarts.getFirst() - 1L, lines, MOVE );
    }

    @Override
    public Optional<? extends List<String>> moveDown( int lines ) {
        return loadFromTop( lineStarts.getLast(), lines, MOVE );
    }

    @Override
    public Optional<? extends List<String>> top() {
        return loadFromTop( 0L, fileWindowSize, REFRESH );
    }

    @Override
    public Optional<? extends List<String>> tail() {
        return loadFromBottom( file.length(), fileWindowSize, REFRESH );
    }

    @Override
    public Optional<? extends List<String>> refresh() {
        long initialLine = lineStarts.getFirst();
        Optional<LinkedList<String>> fromTop = loadFromTop( initialLine, fileWindowSize, REFRESH );
        if ( fromTop.isPresent() ) {
            LinkedList<String> topList = fromTop.get();

            if ( topList.size() < fileWindowSize ) {
                log.trace( "Trying to get more lines after a refresh from the top did not give enough lines" );
                loadFromBottom( initialLine - 1L, fileWindowSize - topList.size(), MOVE )
                        .ifPresent( extraLines -> topList.addAll( 0, extraLines ) );
            }
        }
        return fromTop;
    }

    @Override
    public File getFile() {
        return file;
    }

    private Optional<LinkedList<String>> loadFromTop( Long firstLineStartIndex,
                                                      final int lines,
                                                      final LoadMode mode ) {
        if ( !file.isFile() ) {
            return Optional.empty();
        }

        log.trace( "Loading {} lines from the top, file: {}", lines, file );

        if ( firstLineStartIndex >= file.length() - 1 ) {
            log.trace( "Already at the top of the file, nothing to return" );
            return Optional.of( new LinkedList<>() );
        }

        byte[] buffer = new byte[ bufferSize ];
        LinkedList<String> result = new LinkedList<>();
        byte[] topBytes = new byte[ 0 ];

        try ( RandomAccessFile reader = new RandomAccessFile( file, "r" ) ) {
            if ( mode == LoadMode.REFRESH ) {
                lineStarts.clear();
                firstLineStartIndex = seekLineStartBefore( firstLineStartIndex, reader );
            }

            lineStarts.addLast( firstLineStartIndex );

            log.trace( "Seeking position {}", firstLineStartIndex );
            reader.seek( firstLineStartIndex );

            readerMainLoop:
            while ( true ) {
                final long startIndex = reader.getFilePointer();
                final long lastIndex = reader.length() - 1;
                long fileIndex = startIndex;

                log.trace( "Reading chunk {}..{}",
                        startIndex, startIndex + bufferSize );

                final int bytesRead = reader.read( buffer );
                int lineStartIndex = 0;

                if ( log.isTraceEnabled() && bytesRead > 0 && bytesRead < bufferSize ) {
                    log.trace( "Did not read full buffer, chunk that got read is {}..{}", startIndex, startIndex + bytesRead );
                }

                for ( int i = 0; i < bytesRead; i++ ) {
                    byte b = buffer[ i ];
                    boolean isNewLine = ( b == '\n' );
                    boolean isLastByte = ( fileIndex == lastIndex );

                    if ( isNewLine || isLastByte ) {
                        lineStarts.addLast( startIndex + i + 1 );

                        // if the byte is a new line, don't include it in the result
                        int lineEndIndex = isNewLine ? i - 1 : i;
                        int lineLength = lineEndIndex - lineStartIndex + 1;

                        byte[] lineBytes = new byte[ lineLength + topBytes.length ];
                        log.trace( "Found line, copying [{}:{}] bytes from buffer + {} from top",
                                lineStartIndex, lineLength, topBytes.length );
                        System.arraycopy( topBytes, 0, lineBytes, 0, topBytes.length );
                        System.arraycopy( buffer, lineStartIndex, lineBytes, topBytes.length, lineLength );
                        result.addLast( new String( lineBytes, StandardCharsets.UTF_8 ) );
                        log.trace( "Added line: {}", result.getLast() );

                        if ( result.size() >= lines ) {
                            log.trace( "Got enough lines, breaking out of reader loop" );
                            break readerMainLoop;
                        }

                        topBytes = new byte[ 0 ];
                        lineStartIndex = isNewLine ? i + 1 : i;
                    }

                    fileIndex++;
                }

                if ( bytesRead < 0L ) {
                    log.trace( "Reached file end, breaking out of reader loop" );
                    break;
                }

                // remember the current buffer bytes as the next top bytes
                int bytesToCopy = bufferSize - lineStartIndex;
                byte[] newTop = new byte[ topBytes.length + bytesToCopy ];
                System.arraycopy( buffer, lineStartIndex, newTop, 0, bytesToCopy );
                System.arraycopy( topBytes, 0, newTop, bytesToCopy, topBytes.length );
                log.trace( "Updated top bytes, now top has {} bytes", newTop.length );
                topBytes = newTop;
            }

            log.debug( "Loaded {} lines from file {}", result.size(), file );
            log.trace( "Line starts: {}", lineStarts );
            return Optional.of( result );
        } catch ( IOException e ) {
            log.warn( "Error reading file [{}]: {}", file, e );
            return Optional.empty();
        }
    }

    private Optional<LinkedList<String>> loadFromBottom( final Long firstLineStartIndex,
                                                         final int lines,
                                                         final LoadMode mode ) {
        if ( !file.isFile() ) {
            return Optional.empty();
        }

        log.trace( "Loading {} lines from the bottom of chunk, file: {}", lines, file );

        if ( firstLineStartIndex <= 0L ) {
            log.trace( "Already at the bottom of the file, nothing to return" );
            return Optional.of( new LinkedList<>() );
        }

        byte[] buffer = new byte[ bufferSize ];
        LinkedList<String> result = new LinkedList<>();
        byte[] tailBytes = new byte[ 0 ];
        long bufferStartIndex = firstLineStartIndex;

        try ( RandomAccessFile reader = new RandomAccessFile( file, "r" ) ) {
            if ( mode == LoadMode.REFRESH ) {
                lineStarts.clear();
                bufferStartIndex = seekLineStartBefore( firstLineStartIndex, reader );
                lineStarts.addLast( Math.max( 0L, bufferStartIndex - 1L ) );
            }

            readerMainLoop:
            while ( true ) {
                long previousStartIndex = bufferStartIndex;

                // start reading from the bottom section of the file above the previous position that fits into the buffer
                bufferStartIndex = Math.max( 0, bufferStartIndex - bufferSize );

                log.trace( "Seeking position {}", bufferStartIndex );
                reader.seek( bufferStartIndex );

                log.trace( "Reading chunk {}:{}, previous start: {}",
                        bufferStartIndex, bufferStartIndex + bufferSize, previousStartIndex );

                final int bytesRead = bufferStartIndex == 0L && previousStartIndex > 0 ?
                        reader.read( buffer, 0, ( int ) previousStartIndex ) :
                        reader.read( buffer );

                int lastByteIndex = bytesRead - 1;

                for ( int i = lastByteIndex; i >= 0; i-- ) {
                    byte b = buffer[ i ];
                    boolean isNewLine = ( b == '\n' );
                    boolean firstFileByte = ( bufferStartIndex == 0 && i == 0 );

                    if ( isNewLine || firstFileByte ) {

                        // if the byte is a new line, don't include it in the result
                        int lineStartIndex = isNewLine ? i + 1 : i;
                        int bufferBytesToAdd = lastByteIndex - lineStartIndex + 1;

                        byte[] lineBytes = new byte[ bufferBytesToAdd + tailBytes.length ];
                        log.trace( "Found line, copying {} bytes from buffer + {} from tail", bufferBytesToAdd, tailBytes.length );
                        System.arraycopy( buffer, lineStartIndex, lineBytes, 0, bufferBytesToAdd );
                        System.arraycopy( tailBytes, 0, lineBytes, bufferBytesToAdd, tailBytes.length );
                        result.addFirst( new String( lineBytes, StandardCharsets.UTF_8 ) );
                        log.trace( "Added line: {}", result.getFirst() );

                        tailBytes = new byte[ 0 ];

                        if ( isNewLine ) {
                            lineStarts.addFirst( bufferStartIndex + i + 1 );
                        } else { // this must be the first file byte, remember it
                            lineStarts.addFirst( 0 );
                        }

                        if ( result.size() >= lines ) {
                            log.trace( "Got enough lines, breaking out of the reader loop" );
                            break readerMainLoop;
                        }

                        lastByteIndex = i - 1;
                        log.trace( "Last byte index is now {}", lastByteIndex );
                    }
                }

                if ( bufferStartIndex == 0 ) {
                    log.trace( "Reached file start, breaking out of the reader loop" );
                    break;
                }

                // remember the current buffer bytes as the next tail bytes
                byte[] newTail = new byte[ tailBytes.length + lastByteIndex + 1 ];
                System.arraycopy( buffer, 0, newTail, 0, lastByteIndex + 1 );
                System.arraycopy( tailBytes, 0, newTail, lastByteIndex + 1, tailBytes.length );
                log.trace( "Updated tail, now tail has {} bytes", newTail.length );
                tailBytes = newTail;
            }

            log.debug( "Loaded {} lines from file {}", result.size(), file );
            log.trace( "Line starts: {}", lineStarts );
            return Optional.of( result );
        } catch ( IOException e ) {
            log.warn( "Error reading file [{}]: {}", file, e );
            return Optional.empty();
        }
    }

    private long seekLineStartBefore( Long firstLineStartIndex, RandomAccessFile reader )
            throws IOException {
        log.trace( "Seeking line start before or at {}", firstLineStartIndex );
        if ( firstLineStartIndex == 0L ) {
            return 0L;
        }

        if ( firstLineStartIndex >= reader.length() ) {
            log.trace( "Line start found at EOF, file length = {}", reader.length() );
            return reader.length();
        }

        long index = Math.min( firstLineStartIndex - 1, reader.length() - 1 );
        do {
            reader.seek( index );
            index--;
        } while ( reader.read() != '\n' && index > 0 );

        long result = index == 0L ? 0L : index + 2L;

        log.trace( "Line start before {} found at {}", firstLineStartIndex, result );

        return result;
    }

}
